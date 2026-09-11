package com.epam.aidial.keycloak.helpers.provider;

import com.epam.aidial.keycloak.helpers.model.ProjectGroup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
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
 * response's {@code displayName} serializes as {@code null} — degraded mode still
 * receives the convention-filtered group set. Paginated via {@code @odata.nextLink},
 * immune to the token groups-claim overage problem.
 */
@Slf4j
public class MsGraphProjectGroupsProvider {

    private static final String GRAPH_API_BASE = "https://graph.microsoft.com/v1.0";
    private static final String MEMBER_OF_URL = "%s/me/memberOf/microsoft.graph.group?$filter=%s&$select=id,displayName&$count=true";
    private static final String NEXT_LINK = "@odata.nextLink";

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Fetches all convention-prefixed groups the user is a direct member of (non-transitive,
     * matching the org's membership model).
     *
     * @param accessToken the brokered user's delegated access token
     * @param prefix      the project group name prefix for the server-side filter
     *                    (single quotes are OData-escaped)
     * @return the fetched groups ({@code displayName} null in degraded mode); never null
     * @throws RuntimeException on any Graph error — the caller applies the failure
     *                          semantics (keep previous entitlement, never block login)
     */
    public List<ProjectGroup> fetchProjectGroups(String accessToken, String prefix) {
        String filter = "startswith(displayName,'" + escapeOdata(prefix) + "')";
        String url = String.format(MEMBER_OF_URL, GRAPH_API_BASE, urlEncode(filter));

        List<ProjectGroup> groups = new ArrayList<>();
        String next = url;
        while (next != null) {
            JsonNode page = fetchPage(next, accessToken);
            for (JsonNode entry : page.path("value")) {
                groups.add(new ProjectGroup(
                        entry.path("id").asText(null),
                        entry.hasNonNull("displayName") ? entry.get("displayName").asText() : null));
            }
            next = page.hasNonNull(NEXT_LINK) ? page.get(NEXT_LINK).asText() : null;
        }

        log.debug("Fetched {} project groups for the user", groups.size());
        return groups;
    }

    private JsonNode fetchPage(String url, String accessToken) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("ConsistencyLevel", "eventual"); // required with $filter on directory objects
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                log.error("Microsoft Graph /me/memberOf returned HTTP {}", responseCode);
                throw new RuntimeException("Failed to fetch project groups: HTTP " + responseCode);
            }
            return mapper.readTree(conn.getInputStream());
        } catch (IOException e) {
            log.error("Error calling Microsoft Graph /me/memberOf", e);
            throw new RuntimeException("Error fetching project groups from Microsoft Graph", e);
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
