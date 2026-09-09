package com.epam.aidial.keycloak.helpers.authenticator;

import com.epam.aidial.keycloak.helpers.config.ProjectEntitlementConfiguration;
import jakarta.ws.rs.core.MultivaluedMap;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.List;

/**
 * Selection-capture authenticator (D-019, config-repo spec 02 §2 piece 2): copies
 * the client's {@code project} authorize parameter into the authentication
 * session's <b>client note</b> so it survives to token-mint time.
 *
 * <p>Runs as a REQUIRED browser-flow execution on <b>every</b> authorize — including
 * silent {@code prompt=none} re-authorizes that short-circuit the broker (SSO cookie),
 * which is exactly why the capture is a flow step and not part of the IdP mapper.
 * Priority places it on the <b>first</b> flow pass: the authorize request's query
 * parameters are only present before the broker redirects away; the client note
 * survives the round-trip in the authentication session.
 *
 * <p>Flow-position-agnostic and never breaking: no {@code project} parameter → the
 * note is left untouched (absent note → no claim → the baseline token vocabulary is
 * untouched); multiple values → the first wins (logged). The captured value is
 * client input — untrusted until the protocol mapper validates it against the
 * cached entitlement at every mint.
 */
@Slf4j
public class ProjectSelectionAuthenticator implements Authenticator {

    public static final String CLIENT_NOTE = "PROJECT_SELECTION";

    private static final String DEFAULT_SELECTION_PARAM = "project";

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        String paramName = DEFAULT_SELECTION_PARAM;
        if (context.getAuthenticatorConfig() != null && context.getAuthenticatorConfig().getConfig() != null) {
            paramName = context.getAuthenticatorConfig().getConfig()
                    .getOrDefault(ProjectEntitlementConfiguration.SELECTION_PARAM, DEFAULT_SELECTION_PARAM);
        }

        MultivaluedMap<String, String> params = context.getHttpRequest().getUri().getQueryParameters();
        List<String> values = params != null ? params.get(paramName) : null;
        if (values == null || values.isEmpty()) {
            // No selection in this pass (e.g. the broker callback's resumed request) —
            // leave any previously captured note untouched.
            context.success();
            return;
        }
        if (values.size() > 1) {
            log.warn("Multiple '{}' authorize parameters — first wins", paramName);
        }

        String selection = values.get(0);
        context.getAuthenticationSession().setClientNote(ProjectSelectionAuthenticator.CLIENT_NOTE, selection);
        log.debug("Captured project selection from authorize parameter '{}'", paramName);
        context.success();
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        context.success();
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    }

    @Override
    public void close() {
    }
}
