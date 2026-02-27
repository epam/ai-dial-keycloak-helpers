package com.epam.aidial.keycloak.helpers.protocol;

import org.junit.Test;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.representations.IDToken;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CachedUserAttributesProtocolMapperTest {

    private final CachedUserAttributesProtocolMapper mapper = new CachedUserAttributesProtocolMapper();

    @Test
    public void setClaimAddsJobTitleAndPhotoWhenEnabledAndPresent() {
        ProtocolMapperModel mappingModel = mock(ProtocolMapperModel.class);
        Map<String, String> config = new HashMap<>();
        config.put("fetch.job.title", "true");
        config.put("fetch.photo", "true");
        when(mappingModel.getConfig()).thenReturn(config);

        UserModel user = mock(UserModel.class);
        when(user.getAttributeStream("jobTitle")).thenReturn(Stream.of("Engineer"));
        when(user.getAttributeStream("picture")).thenReturn(Stream.of("data:image/jpeg;base64,xxx"));

        UserSessionModel userSession = mock(UserSessionModel.class);
        when(userSession.getUser()).thenReturn(user);

        IDToken token = new IDToken();

        mapper.setClaim(token, mappingModel, userSession,
                mock(KeycloakSession.class), mock(ClientSessionContext.class));

        assertEquals("Engineer", token.getOtherClaims().get("job_title"));
        assertEquals("data:image/jpeg;base64,xxx", token.getOtherClaims().get("picture"));
    }

    @Test
    public void setClaimSkipsClaimsWhenDisabled() {
        ProtocolMapperModel mappingModel = mock(ProtocolMapperModel.class);
        Map<String, String> config = new HashMap<>();
        config.put("fetch.job.title", "false");
        config.put("fetch.photo", "false");
        when(mappingModel.getConfig()).thenReturn(config);

        UserModel user = mock(UserModel.class);
        when(user.getAttributeStream("jobTitle")).thenReturn(Stream.of("Engineer"));
        when(user.getAttributeStream("picture")).thenReturn(Stream.of("data:image/jpeg;base64,xxx"));

        UserSessionModel userSession = mock(UserSessionModel.class);
        when(userSession.getUser()).thenReturn(user);

        IDToken token = new IDToken();

        mapper.setClaim(token, mappingModel, userSession,
                mock(KeycloakSession.class), mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("job_title"));
        assertFalse(token.getOtherClaims().containsKey("picture"));
    }

    @Test(expected = IllegalStateException.class)
    public void setClaimThrowsWhenMultipleAttributeValues() {
        ProtocolMapperModel mappingModel = mock(ProtocolMapperModel.class);
        Map<String, String> config = new HashMap<>();
        config.put("fetch.job.title", "true");
        config.put("fetch.photo", "true");
        when(mappingModel.getConfig()).thenReturn(config);

        UserModel user = mock(UserModel.class);
        when(user.getUsername()).thenReturn("testuser");
        when(user.getAttributeStream("jobTitle")).thenReturn(Stream.of("Engineer", "Manager"));

        UserSessionModel userSession = mock(UserSessionModel.class);
        when(userSession.getUser()).thenReturn(user);

        IDToken token = new IDToken();

        mapper.setClaim(token, mappingModel, userSession,
                mock(KeycloakSession.class), mock(ClientSessionContext.class));
    }

    @Test
    public void setClaimSkipsWhenAttributeIsEmpty() {
        ProtocolMapperModel mappingModel = mock(ProtocolMapperModel.class);
        Map<String, String> config = new HashMap<>();
        config.put("fetch.job.title", "true");
        config.put("fetch.photo", "true");
        when(mappingModel.getConfig()).thenReturn(config);

        UserModel user = mock(UserModel.class);
        when(user.getAttributeStream("jobTitle")).thenReturn(Stream.empty());
        when(user.getAttributeStream("picture")).thenReturn(Stream.empty());

        UserSessionModel userSession = mock(UserSessionModel.class);
        when(userSession.getUser()).thenReturn(user);

        IDToken token = new IDToken();

        mapper.setClaim(token, mappingModel, userSession,
                mock(KeycloakSession.class), mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("job_title"));
        assertFalse(token.getOtherClaims().containsKey("picture"));
    }
}