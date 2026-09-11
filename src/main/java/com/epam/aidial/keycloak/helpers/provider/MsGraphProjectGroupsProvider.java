package com.epam.aidial.keycloak.helpers.provider;

import com.epam.aidial.keycloak.helpers.model.ProjectGroup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches the brokered user's project groups from Microsoft Graph —
 * {@code /me/memberOf/microsoft.graph.group} with the <b>user's own delegated
 * token</b> (documented least privilege: delegated {@code User.Read}; no
 * application permissions, D-019 config-repo spec 02 §3).
 *
 * <p>The server-side {@code startswith(displayName,'<prefix>')} advanced filter
 * (OData cast + {@code ConsistencyLevel: eventual}) is applied even when the
 * response's {@code displayName} serializes as {@code null} — the per-group
 * fallback still receives the convention-filtered group set. Paginated via
 * {@code @odata.nextLink}, immune to the token groups-claim overage problem.
 *
 * <p><b>Hardened (amended 2026-09-11)</b>: explicit connect/read timeouts;
 * {@code $top=999} page size; a fixed page cap — a pagination chain beyond it
 * fails as a <b>temporary</b> failure (the caller keeps the previous
 * entitlement); the {@code @odata.nextLink} chain is followed <b>only</b> while
 * the link points at the Microsoft Graph host — a link on any other host is
 * refused, so pagination never leaves Graph; error streams are always closed
 * and the connection always disconnected. Stays on the JDK's
 * {@code HttpURLConnection} (the upstream pattern); an HTTP-client migration is
 * a recorded follow-up, not built here.
 */
@Slf4j
public class MsGraphProjectGroupsProvider {

    private static final String GRAPH_API_BASE = "https://graph.microsoft.com/v1.0";
    private static final String NEXT_LINK_ALLOWED_PREFIX = "https://graph.microsoft.com/";
    private static final String MEMBER_OF_URL = "%s/me/memberOf/microsoft.graph.group?$filter=%s&$select=id,displayName&$count=true&$top=999";
    private static final String NEXT_LINK = "@odata.nextLink";

    /** Default connect timeout (milliseconds). */
    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000;
    /** Default read timeout (milliseconds). */
    public static final int DEFAULT_READ_TIMEOUT_MILLIS = 10_000;
    /** The fixed maximum number of pages fetched per call — a longer chain is a temporary failure. */
    public static final int MAX_PAGES = 20;

    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiBase;
    private final String nextLinkAllowedPrefix;

    /** The production provider — Microsoft Graph only. */
    public MsGraphProjectGroupsProvider() {
        this(GRAPH_API_BASE, NEXT_LINK_ALLOWED_PREFIX);
    }

    /**
     * Base-URL injection for tests — a local server stands in for Graph and for
     * the allowed nextLink host, so no test ever calls the real service.
     */
    MsGraphProjectGroupsProvider(String apiBase, String nextLinkAllowedPrefix) {
        this.apiBase = apiBase;
        this.nextLinkAllowedPrefix = nextLinkAllowedPrefix;
    }

    /**
     * Fetches all convention-prefixed groups the user is a direct member of (non-transitive,
     * matching the org's membership model) with the default timeouts.
     *
     * @param accessToken the brokered user's delegated access token
     * @param prefix      the project group name prefix for the server-side filter
     *                    (single quotes are OData-escaped)
     * @return the fetched groups ({@code displayName} null when not readable); never null
     * @throws GraphFetchException on any Graph error — the caller applies the
     *                             failure semantics (lasting/temporary split)
     */
    public List<ProjectGroup> fetchProjectGroups(String accessToken, String prefix) {
        return fetchProjectGroups(accessToken, prefix, DEFAULT_CONNECT_TIMEOUT_MILLIS, DEFAULT_READ_TIMEOUT_MILLIS);
    }

    /**
     * As above with explicit connect/read timeouts (milliseconds, mapper-configurable).
     */
    public List<ProjectGroup> fetchProjectGroups(String accessToken, String prefix,
                                                 int connectTimeoutMillis, int readTimeoutMillis) {
        String filter = "startswith(displayName,'" + escapeOdata(prefix) + "')";
        String url = String.format(MEMBER_OF_URL, apiBase, urlEncode(filter));

        List<ProjectGroup> groups = new ArrayList<>();
        String next = url;
        int pages = 0;
        while (next != null) {
            pages++;
            if (pages > MAX_PAGES) {
                log.error("Microsoft Graph pagination exceeded the {} page cap — treating as a temporary failure", MAX_PAGES);
                throw new GraphFetchException("Project group pagination exceeded the page cap (" + MAX_PAGES + " pages)", 0);
            }
            JsonNode page = fetchPage(next, accessToken, connectTimeoutMillis, readTimeoutMillis);
            for (JsonNode entry : page.path("value")) {
                groups.add(new ProjectGroup(
                        entry.path("id").asText(null),
                        entry.hasNonNull("displayName") ? entry.get("displayName").asText() : null));
            }
            next = nextPageLink(page);
        }

        log.debug("Fetched {} project groups for the user ({} page(s))", groups.size(), pages);
        return groups;
    }

    /**
     * The next page's link — only ever followed when it points back at the
     * allowed Graph host; anything else is refused (pagination never leaves Graph).
     */
    private String nextPageLink(JsonNode page) {
        if (!page.hasNonNull(NEXT_LINK)) {
            return null;
        }
        String link = page.get(NEXT_LINK).asText();
        if (!link.startsWith(nextLinkAllowedPrefix)) {
            log.error("Refusing to follow a @odata.nextLink off the Microsoft Graph host — pagination never leaves Graph");
            throw new GraphFetchException("Project group pagination left the Microsoft Graph host — refused", 0);
        }
        return link;
    }

    private JsonNode fetchPage(String url, String accessToken, int connectTimeoutMillis, int readTimeoutMillis) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(connectTimeoutMillis);
            conn.setReadTimeout(readTimeoutMillis);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("ConsistencyLevel", "eventual"); // required with $filter on directory objects
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                closeQuietly(conn.getErrorStream());
                log.error("Microsoft Graph /me/memberOf returned HTTP {}", responseCode);
                throw new GraphFetchException("Failed to fetch project groups: HTTP " + responseCode, responseCode);
            }
            return mapper.readTree(conn.getInputStream());
        } catch (IOException e) {
            log.error("Error calling Microsoft Graph /me/memberOf", e);
            throw new GraphFetchException("Error fetching project groups from Microsoft Graph", 0, e);
        } finally {
            if (conn != null) {
                conn.disconnect(); // closes the streams with it — no leaked connections
            }
        }
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException e) {
            log.debug("Error stream already closed", e);
        }
    }

    private String escapeOdata(String value) {
        return value.replace("'", "''");
    }

    private String urlEncode(String value) {
        try {
            // URLEncoder is form-encoding — '+' would corrupt the filter; Graph expects %20.
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("UTF-8 unsupported", e);
        }
    }
}
