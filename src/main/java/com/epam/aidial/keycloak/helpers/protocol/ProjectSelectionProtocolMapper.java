package com.epam.aidial.keycloak.helpers.protocol;

import com.epam.aidial.keycloak.helpers.config.ProjectEntitlementConfiguration;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.MultivaluedMap;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakContext;
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
 * Validate-and-emit protocol mapper (D-019, config-repo spec 02 §2 piece 2 of 2):
 * at <b>every token mint</b> reads the selection from <b>the request itself</b> —
 * the {@code project} form parameter of the exchange/refresh POST this mint
 * belongs to (untrusted client input) — and the user's cached entitlement
 * (server data fetched by the entitlement IdP mapper).
 *
 * <p><b>selection ∈ entitlement → emit {@code project: "<selection>"}</b> as a plain
 * JSON string (access token only); <b>else → emit nothing</b> — HTTP 200, well-formed
 * token, no error surface. The silent-drop semantics the client's mandatory
 * claim-verification detects (config-repo spec 03 / the P3/P4 CLI MUST).
 *
 * <p>Validation is a set-membership comparison — the selection is never interpolated,
 * parsed, or executed (no injection surface). A malformed cached entitlement fails
 * closed (no claim).
 *
 * <p><b>No IdP session state is read or written</b> (the 2026-09-10 re-mint-defect
 * amendment, config-repo spec 02 §4): the request is the ONLY selection source —
 * no param → no claim, uniformly, with no fallback. The former
 * {@code PROJECT_SELECTION} client-session note was frozen at the SSO user
 * session's first consumer-client authorize and re-emitted cross-session (the
 * live E2E falsification); the note machinery is <b>removed, not repaired</b> —
 * per-grant requests carry their selection explicitly (authorize + exchange +
 * every refresh POST, the RFC 6749 §6 shape), so parallel sessions are isolated
 * by construction and a mid-session aliasing has no shared state to arise from.
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
        return "Emits the request's selected project (the 'project' form parameter of the exchange/refresh POST) "
                + "as the singular 'project' claim when the selection is within the user's cached project "
                + "entitlement; emits nothing otherwise, and when the request carries no selection "
                + "(silent drop — no param → no claim, uniformly)";
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

        // Selection source: the REQUEST only (config-repo spec 02 §4, amended
        // 2026-09-10). Guarded — the context/httpRequest may be null outside
        // request scope (service-account/offline mints) → no claim.
        String selection = requestFormParam(keycloakSession, config.getSelectionParam());

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
            log.debug("Emitted project claim for the request's entitled selection");
        } else {
            log.debug("Selection not within the user's entitlement — no claim emitted (silent drop)");
        }
    }

    /**
     * The {@code paramName} form parameter of THIS mint's own request, or
     * {@code null} when absent or when no request scope exists. The accessor is
     * the documented Quarkus/Resteasy pattern (the decoded form parameters are
     * cached after the token endpoint's own {@code @FormParam} parsing) — its
     * live behavior on Keycloak 26.7.2 is proven by the rig's re-mint proof
     * (config-repo spec 02 §9, implementation-time unknown (a)).
     */
    private static String requestFormParam(KeycloakSession keycloakSession, String paramName) {
        if (keycloakSession == null) {
            return null;
        }
        KeycloakContext context = keycloakSession.getContext();
        if (context == null) {
            return null;
        }
        HttpRequest request = context.getHttpRequest();
        if (request == null) {
            return null;
        }
        MultivaluedMap<String, String> formParams = request.getDecodedFormParameters();
        if (formParams == null) {
            return null;
        }
        return formParams.getFirst(paramName);
    }
}
