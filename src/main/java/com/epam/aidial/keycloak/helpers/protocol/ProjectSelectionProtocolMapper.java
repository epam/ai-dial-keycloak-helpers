package com.epam.aidial.keycloak.helpers.protocol;

import com.epam.aidial.keycloak.helpers.authenticator.ProjectSelectionAuthenticator;
import com.epam.aidial.keycloak.helpers.config.ProjectEntitlementConfiguration;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.IDToken;

import java.util.ArrayList;
import java.util.List;

/**
 * Validate-and-emit protocol mapper (D-019, config-repo spec 02 §2 piece 3): at
 * <b>every token mint</b> reads the session's captured selection (client note set by
 * {@link ProjectSelectionAuthenticator} — untrusted client input) and the user's
 * cached entitlement (server data fetched by the entitlement IdP mapper).
 *
 * <p><b>selection ∈ entitlement → emit {@code project: "<selection>"}</b> as a plain
 * JSON string (access token only); <b>else → emit nothing</b> — HTTP 200, well-formed
 * token, no error surface. The silent-drop semantics the client's mandatory
 * claim-verification detects (config-repo spec 03 / the P3/P4 CLI MUST).
 *
 * <p>Validation is a set-membership comparison — the selection is never interpolated,
 * parsed, or executed (no injection surface). Selection is fixed per client session;
 * refreshes re-emit it; parallel sessions carry their own. A malformed cached
 * entitlement fails closed (no claim).
 */
@Slf4j
public class ProjectSelectionProtocolMapper extends AbstractOIDCProtocolMapper
        implements OIDCAccessTokenMapper {

    public static final String PROVIDER_ID = "project-selection-protocol-mapper";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Project Selection (OIDC Claim)";
    }

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;
    }

    @Override
    public String getHelpText() {
        return "Emits the session's selected project as the singular 'project' claim when the selection "
                + "is within the user's cached project entitlement; emits nothing otherwise (silent drop)";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        List<ProviderConfigProperty> properties =
                new ArrayList<>(ProjectEntitlementConfiguration.getConfigProperties());
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(properties, ProjectSelectionProtocolMapper.class);
        return properties;
    }

    @Override
    protected void setClaim(IDToken token, ProtocolMapperModel mappingModel,
                            UserSessionModel userSession, KeycloakSession keycloakSession,
                            ClientSessionContext clientSessionCtx) {

        if (!(token instanceof AccessToken)) {
            return; // access-token-only by design (HARD RULE: the claim transport stays out of id_token/userinfo)
        }

        ProjectEntitlementConfiguration config = ProjectEntitlementConfiguration.fromModel(mappingModel);

        AuthenticatedClientSessionModel clientSession = clientSessionCtx.getClientSession();
        String selection = clientSession != null ? clientSession.getNote(ProjectSelectionAuthenticator.CLIENT_NOTE) : null;
        String entitlementJson = userSession.getUser().getFirstAttribute(config.getEntitlementAttribute());
        if (selection == null || selection.isEmpty() || entitlementJson == null) {
            return;
        }

        List<String> entitlement;
        try {
            entitlement = objectMapper.readValue(entitlementJson, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            log.warn("Cached project entitlement is malformed — emitting no claim (fail closed)");
            return;
        }

        if (entitlement.contains(selection)) {
            token.getOtherClaims().put(config.getClaimName(), selection);
            log.debug("Emitted project claim for entitled selection");
        } else {
            log.debug("Selection not within the user's entitlement — no claim emitted (silent drop)");
        }
    }
}
