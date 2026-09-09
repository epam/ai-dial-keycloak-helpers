package com.epam.aidial.keycloak.helpers.authenticator;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.Test;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.http.HttpRequest;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ProjectSelectionAuthenticatorTest {

    private final ProjectSelectionAuthenticator authenticator = new ProjectSelectionAuthenticator();

    private AuthenticationFlowContext contextFor(MultivaluedMap<String, String> queryParameters) {
        AuthenticationFlowContext context = mock(AuthenticationFlowContext.class);
        HttpRequest request = mock(HttpRequest.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(context.getHttpRequest()).thenReturn(request);
        when(request.getUri()).thenReturn(uriInfo);
        when(uriInfo.getQueryParameters()).thenReturn(queryParameters);
        when(context.getAuthenticatorConfig()).thenReturn(null);
        return context;
    }

    @Test
    public void capturesProjectParameterIntoClientNote() {
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>(Map.of("project", "EPM-AEM"));
        AuthenticationFlowContext context = contextFor(params);
        AuthenticationSessionModel authSession = mock(AuthenticationSessionModel.class);
        when(context.getAuthenticationSession()).thenReturn(authSession);

        authenticator.authenticate(context);

        verify(authSession).setClientNote(ProjectSelectionAuthenticator.CLIENT_NOTE, "EPM-AEM");
        verify(context).success();
    }

    @Test
    public void absentParameterLeavesClientNoteUntouched() {
        AuthenticationFlowContext context = contextFor(new MultivaluedHashMap<>());
        AuthenticationSessionModel authSession = mock(AuthenticationSessionModel.class);
        when(context.getAuthenticationSession()).thenReturn(authSession);

        authenticator.authenticate(context);

        verify(authSession, never()).setClientNote(anyString(), anyString());
        verify(context).success();
    }

    @Test
    public void multipleValuesFirstWins() {
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        params.put("project", List.of("EPM-AEM", "EPM-OTHER"));
        AuthenticationFlowContext context = contextFor(params);
        AuthenticationSessionModel authSession = mock(AuthenticationSessionModel.class);
        when(context.getAuthenticationSession()).thenReturn(authSession);

        authenticator.authenticate(context);

        verify(authSession).setClientNote(ProjectSelectionAuthenticator.CLIENT_NOTE, "EPM-AEM");
        verify(context).success();
    }
}
