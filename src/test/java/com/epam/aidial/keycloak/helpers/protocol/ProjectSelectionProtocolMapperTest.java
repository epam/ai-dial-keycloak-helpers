package com.epam.aidial.keycloak.helpers.protocol;

import com.epam.aidial.keycloak.helpers.authenticator.ProjectSelectionAuthenticator;
import org.junit.Test;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientSessionContext;
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

    private ClientSessionContext clientSessionWithSelection(String selection) {
        AuthenticatedClientSessionModel clientSession = mock(AuthenticatedClientSessionModel.class);
        when(clientSession.getNote(ProjectSelectionAuthenticator.CLIENT_NOTE)).thenReturn(selection);
        ClientSessionContext ctx = mock(ClientSessionContext.class);
        when(ctx.getClientSession()).thenReturn(clientSession);
        return ctx;
    }

    @Test
    public void entitledSelectionEmitsClaim() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                mock(KeycloakSession.class), clientSessionWithSelection("EPM-AEM"));

        assertEquals("EPM-AEM", token.getOtherClaims().get("project"));
    }

    @Test
    public void unentitledSelectionSilentlyDropped() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                mock(KeycloakSession.class), clientSessionWithSelection("NOT-MINE"));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void absentSelectionEmitsNothing() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                mock(KeycloakSession.class), clientSessionWithSelection(null));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void absentEntitlementEmitsNothing() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(null),
                mock(KeycloakSession.class), clientSessionWithSelection("EPM-AEM"));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void malformedEntitlementFailsClosed() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement("not-json"),
                mock(KeycloakSession.class), clientSessionWithSelection("EPM-AEM"));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void idTokenNeverReceivesTheClaim() {
        IDToken idToken = new IDToken();

        mapper.setClaim(idToken, mappingModel(), userSessionWithEntitlement(ENTITLED),
                mock(KeycloakSession.class), clientSessionWithSelection("EPM-AEM"));

        assertFalse(idToken.getOtherClaims().containsKey("project"));
    }

    @Test
    public void claimIsSingularStringEvenForMultiProjectEntitlement() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                mock(KeycloakSession.class), clientSessionWithSelection("EPM-AEM"));

        Object claim = token.getOtherClaims().get("project");
        assertTrue(claim instanceof String);
        assertEquals("EPM-AEM", claim);
    }
}
