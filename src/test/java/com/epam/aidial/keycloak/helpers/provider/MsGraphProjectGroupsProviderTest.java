package com.epam.aidial.keycloak.helpers.provider;

import com.epam.aidial.keycloak.helpers.model.ProjectGroup;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Graph-call hardening (amended 2026-09-11) — against a LOCAL test server, no
 * external calls: explicit read timeouts fire on a no-response endpoint; a
 * pagination chain beyond the page cap fails as a temporary failure; a
 * {@code @odata.nextLink} pointing off the allowed host is refused, not
 * followed; an error response raises the typed exception with its status.
 * The provider under test is constructed with the local server as BOTH the API
 * base and the allowed nextLink prefix — production constructs the Graph-only
 * default.
 */
public class MsGraphProjectGroupsProviderTest {

    private HttpServer server;
    private String base;
    private final AtomicInteger requests = new AtomicInteger();

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1.0";
        server.start();
    }

    @After
    public void stopServer() {
        server.stop(0);
    }

    private void respondWith(String body) {
        respondWith(body, 200);
    }

    private void respondWith(String body, int status) {
        requests.set(0);
        server.createContext("/v1.0/me/memberOf", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length());
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        });
    }

    private void respondNever() {
        requests.set(0);
        server.createContext("/v1.0/me/memberOf", exchange -> {
            requests.incrementAndGet();
            // never respond — the read timeout must fire
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Test
    public void fetchesAndParsesOnePage() {
        respondWith("{\"value\":[{\"id\":\"id-1\",\"displayName\":\"Project EPM-AEM\"},{\"id\":\"id-2\",\"displayName\":null}]}");

        List<ProjectGroup> groups = provider().fetchProjectGroups("token", "Project ");

        // The provider returns raw Graph groups — the convention parsing lives in ProjectEntitlement.
        assertEquals(2, groups.size());
        assertEquals("id-1", groups.get(0).getId());
        assertEquals("Project EPM-AEM", groups.get(0).getDisplayName());
        assertEquals("id-2", groups.get(1).getId());
        assertNull(groups.get(1).getDisplayName());
    }

    @Test
    public void requestsTop999AndFollowsSameHostPagination() {
        // Two pages, both on the allowed host (here: the local server standing in for Graph).
        String[] firstQuery = new String[1];
        server.createContext("/v1.0/me/memberOf", exchange -> {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            if (path.endsWith("/page2")) {
                body = "{\"value\":[{\"id\":\"id-2\",\"displayName\":\"Project ABC-42\"}]}".getBytes(StandardCharsets.UTF_8);
            } else {
                firstQuery[0] = exchange.getRequestURI().getQuery();
                body = ("{\"value\":[{\"id\":\"id-1\",\"displayName\":\"Project EPM-AEM\"}],"
                        + "\"@odata.nextLink\":\"" + base + "/me/memberOf/page2?$skiptoken=abc\"}").getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        List<ProjectGroup> groups = provider().fetchProjectGroups("token", "Project ");

        assertEquals(2, requests.get()); // the follow happened
        assertEquals(2, groups.size());
        assertTrue("expected $top=999 on the initial request", firstQuery[0].contains("$top=999"));
    }

    @Test
    public void pageCapExcessIsTemporary() {
        // Every page advertises another page on the allowed host — the chain can
        // never end; the cap must stop it as a temporary failure (statusCode 0).
        server.createContext("/v1.0/me/memberOf", exchange -> {
            requests.incrementAndGet();
            byte[] body = ("{\"value\":[{\"id\":\"id-1\",\"displayName\":\"Project EPM-AEM\"}],"
                    + "\"@odata.nextLink\":\"" + base + "/me/memberOf/next?$skiptoken=abc\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        try {
            provider().fetchProjectGroups("token", "Project ");
            throw new AssertionError("expected the page cap to fail the fetch");
        } catch (GraphFetchException e) {
            assertEquals(0, e.getStatusCode());
            assertFalse(e.isLasting()); // temporary — the caller keeps the previous entitlement
            assertEquals(MsGraphProjectGroupsProvider.MAX_PAGES, requests.get());
        }
    }

    @Test
    public void foreignHostNextLinkIsRefusedNotFollowed() {
        // The link points off the allowed host — refused outright, no second request.
        respondWith("{\"value\":[{\"id\":\"id-1\",\"displayName\":\"Project EPM-AEM\"}],"
                + "\"@odata.nextLink\":\"https://evil.example.com/v1.0/me/memberOf?$skiptoken=abc\"}");

        try {
            provider().fetchProjectGroups("token", "Project ");
            throw new AssertionError("expected a foreign-host nextLink to be refused");
        } catch (GraphFetchException e) {
            assertFalse(e.isLasting()); // temporary
            assertEquals(1, requests.get()); // never followed
        }
    }

    @Test
    public void noResponseEndpointHitsTheReadTimeout() {
        respondNever();

        int tinyReadTimeout = 300;
        try {
            provider().fetchProjectGroups("token", "Project ",
                    MsGraphProjectGroupsProvider.DEFAULT_CONNECT_TIMEOUT_MILLIS, tinyReadTimeout);
            throw new AssertionError("expected the read timeout to fire");
        } catch (GraphFetchException e) {
            assertFalse(e.isLasting()); // a timeout is a temporary failure
            assertTrue(e.getCause() instanceof java.net.SocketTimeoutException);
        }
    }

    @Test
    public void errorResponseCarriesLastingStatus() {
        respondWith("{\"error\":{\"code\":\"Authorization_RequestDenied\"}}", 403);

        try {
            provider().fetchProjectGroups("token", "Project ");
            throw new AssertionError("expected HTTP 403 to raise the typed exception");
        } catch (GraphFetchException e) {
            assertEquals(403, e.getStatusCode());
            assertTrue(e.isLasting()); // the caller clears the cache
        }
    }

    private MsGraphProjectGroupsProvider provider() {
        // The local server stands in for Graph AND for the allowed nextLink host —
        // the foreign-host case points at https://graph.microsoft.com/ instead.
        return new MsGraphProjectGroupsProvider(base, base);
    }
}
