package com.epam.aidial.keycloak.helpers.protocol;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.Test;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.IDToken;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Request-carried selection contract (config-repo spec 02 §4, amended
 * 2026-09-10): the mint-side selection source is the REQUEST's {@code project}
 * form parameter — never IdP session state. No param → no claim, uniformly;
 * the mapper emits only when the request's selection ∈ the cached entitlement.
 */
public class ProjectSelectionProtocolMapperTest {

    private final ProjectSelectionProtocolMapper mapper = new ProjectSelectionProtocolMapper();

    private static final String ENTITLED = "[\"EPM-AEM\",\"ABC-42\"]";

    private ProtocolMapperModel mappingModel() {
        ProtocolMapperModel mappingModel = mock(ProtocolMapperModel.class);
        Map<String, String> config = new HashMap<>();
        config.put("claim.name", "project");
        when(mappingModel.getConfig()).thenReturn(config);
        return mappingModel;
    }

    private UserSessionModel userSessionWithEntitlement(String entitlementJson) {
        UserModel user = mock(UserModel.class);
        when(user.getFirstAttribute("projectEntitlement")).thenReturn(entitlementJson);
        UserSessionModel userSession = mock(UserSessionModel.class);
        when(userSession.getUser()).thenReturn(user);
        return userSession;
    }

    /**
     * A KeycloakSession whose context carries an HTTP request with the given
     * form parameters — the mint-side selection source (the exchange/refresh
     * POST body as the token endpoint decoded it).
     */
    private KeycloakSession sessionWithFormParams(MultivaluedMap<String, String> formParams) {
        HttpRequest request = mock(HttpRequest.class);
        when(request.getDecodedFormParameters()).thenReturn(formParams);
        KeycloakContext context = mock(KeycloakContext.class);
        when(context.getHttpRequest()).thenReturn(request);
        KeycloakSession session = mock(KeycloakSession.class);
        when(session.getContext()).thenReturn(context);
        return session;
    }

    private KeycloakSession sessionWithFormParam(String name, String value) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        if (value != null) {
            params.add(name, value);
        }
        return sessionWithFormParams(params);
    }

    @Test
    public void requestParamWithinEntitlementEmitsClaim() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                sessionWithFormParam("project", "EPM-AEM"), mock(ClientSessionContext.class));

        assertEquals("EPM-AEM", token.getOtherClaims().get("project"));
    }

    @Test
    public void unentitledRequestParamSilentlyDropped() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                sessionWithFormParam("project", "NOT-MINE"), mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void absentRequestParamEmitsNothing() {
        AccessToken token = new AccessToken();

        // No param → no claim, uniformly (no fallback, no session state — the
        // 2026-09-10 ruling; a param-less client's tokens stay baseline).
        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                sessionWithFormParam("project", null), mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void absentEntitlementEmitsNothing() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(null),
                sessionWithFormParam("project", "EPM-AEM"), mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void malformedEntitlementFailsClosed() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement("not-json"),
                sessionWithFormParam("project", "EPM-AEM"), mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void nullRequestContextEmitsNothing() {
        AccessToken token = new AccessToken();

        // Guarded: mints outside request scope (service-account/offline paths)
        // read no session state either — they simply emit no claim.
        KeycloakSession noRequestContext = mock(KeycloakSession.class);
        when(noRequestContext.getContext()).thenReturn(null);

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                noRequestContext, mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void nullSessionEmitsNothing() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                null, mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void idTokenNeverReceivesTheClaim() {
        IDToken idToken = new IDToken();

        mapper.setClaim(idToken, mappingModel(), userSessionWithEntitlement(ENTITLED),
                sessionWithFormParam("project", "EPM-AEM"), mock(ClientSessionContext.class));

        assertFalse(idToken.getOtherClaims().containsKey("project"));
    }

    @Test
    public void claimIsSingularStringEvenForMultiProjectEntitlement() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                sessionWithFormParam("project", "EPM-AEM"), mock(ClientSessionContext.class));

        Object claim = token.getOtherClaims().get("project");
        assertTrue(claim instanceof String);
        assertEquals("EPM-AEM", claim);
    }
}
