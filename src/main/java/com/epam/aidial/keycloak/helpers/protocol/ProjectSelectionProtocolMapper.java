package com.epam.aidial.keycloak.helpers.protocol;

import com.epam.aidial.keycloak.helpers.config.ProjectEntitlementConfiguration;
import com.epam.aidial.keycloak.helpers.idp.ProjectEntitlementIdpMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.MultivaluedMap;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.broker.provider.IdentityProviderMapperSyncModeDelegate;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.IdentityProviderSyncMode;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.IDToken;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPAttributePermissions;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.keycloak.userprofile.UserProfileProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

    private static final String ROLE_USER = "user";
    private static final String ROLE_ANONYMOUS = "anonymous";

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
        RealmModel realm = realmOf(keycloakSession);

        // Attribute-premise guard (the 2026-09-11 review hardening): the cached
        // entitlement is a plain user attribute used as the premise of an
        // enforcement decision — its write surface is realm policy (the User
        // Profile declaration), and a broken premise fails CLOSED here: no
        // claim, loudly. Detection, not prevention — no token mapper can defend
        // a realm's attribute-write surface.
        if (!attributePremiseHolds(realm, keycloakSession, config.getEntitlementAttribute())) {
            return;
        }

        // Sync guard (the 2026-09-11 review hardening): the entitlement refresh
        // runs only under effective sync mode FORCE or LEGACY — a frozen (IMPORT)
        // cache must never mint silently, and a missing feeder never refreshes at
        // all. Both fail CLOSED here.
        if (!syncPremiseHolds(realm)) {
            return;
        }

        // Selection source: the REQUEST only (config-repo spec 02 §4, amended
        // 2026-09-10). Guarded — the context/httpRequest may be null outside
        // request scope (service-account/offline mints) → no claim.
        String selection = requestFormParam(keycloakSession, config.getSelectionParam());

        String entitlementJson = userSession.getUser().getFirstAttribute(config.getEntitlementAttribute());
        if (selection == null || selection.isEmpty() || entitlementJson == null) {
            return;
        }

        // Freshness bound (the 2026-09-11 review hardening): when enabled, a cache
        // older than the bound — or without a fetch timestamp — is treated as
        // ABSENT → no claim (fail closed, never fail open). Caches the idle time
        // of a correctly-refreshing mapper; a re-login refreshes and cures.
        if (config.getEntitlementMaxAgeMinutes() > 0 && !entitlementCacheIsFresh(userSession.getUser(), config)) {
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
     * The realm's User Profile must declare the entitlement attribute and it
     * must not be user-editable — an undeclared or user-writable attribute
     * fails the mint's premise (self-written entitlements would mint claims).
     * Resolves the realm configuration at the point of use; when the premise
     * cannot be verified (no realm/session scope) it fails closed. Returns
     * {@code true} only when the premise holds.
     */
    private boolean attributePremiseHolds(RealmModel realm, KeycloakSession keycloakSession, String attribute) {
        if (realm == null) {
            log.error("No realm scope — the entitlement attribute '{}' premise cannot be verified — emitting no claim (fail closed)", attribute);
            return false;
        }
        UserProfileProvider profileProvider = keycloakSession.getProvider(UserProfileProvider.class);
        UPConfig profileConfig = profileProvider == null ? null : profileProvider.getConfiguration();
        UPAttribute declaration = attributeDeclaration(profileConfig, attribute);
        if (declaration == null) {
            log.error("Entitlement attribute '{}' is not declared in the realm's User Profile — emitting no claim (fail closed). "
                    + "Declare it admin-only (edit: admin) — an undeclared attribute is writable by users when unmanaged attributes are enabled", attribute);
            return false;
        }
        UPAttributePermissions permissions = declaration.getPermissions();
        Set<String> edit = permissions == null ? Set.of() : permissions.getEdit();
        if (edit.contains(ROLE_USER) || edit.contains(ROLE_ANONYMOUS)) {
            log.error("Entitlement attribute '{}' is user-editable in the realm's User Profile (edit: {}) — emitting no claim (fail closed). "
                    + "Restrict the declaration to admin-only edit — a user-writable entitlement attribute is an enforcement-premise defect", attribute, edit);
            return false;
        }
        return true;
    }

    /**
     * The two mappers (the entitlement fetch mapper and this one) are a matched
     * set: the realm must hold at least one entitlement fetch-mapper instance —
     * the feeder the cached attribute would never refresh without — and every
     * one of them must sit at an <b>effective</b> sync mode of FORCE or LEGACY.
     * The effective mode is computed with Keycloak's own
     * {@code IdentityProviderMapperSyncModeDelegate.combineIdpAndMapperSyncMode}
     * — the exact resolution the brokered-login path performs (zero drift): a
     * mapper on INHERIT takes its federation's mode, and an unset federation
     * mode resolves to LEGACY (which refreshes every login — benign; the frozen
     * trap is an effective IMPORT). Resolves the realm configuration at the
     * point of use; there is no brokered context at mint to read instead.
     * Returns {@code true} only when the premise holds.
     */
    private boolean syncPremiseHolds(RealmModel realm) {
        if (realm == null) {
            log.error("No realm scope — the entitlement fetch-mapper premise cannot be verified — emitting no claim (fail closed)");
            return false;
        }
        List<IdentityProviderMapperModel> feeders = realm.getIdentityProviderMappersStream()
                .filter(mapperModel -> ProjectEntitlementIdpMapper.PROVIDER_ID.equals(mapperModel.getIdentityProviderMapper()))
                .toList();
        if (feeders.isEmpty()) {
            log.error("No entitlement fetch-mapper instance is configured in the realm — the entitlement attribute has no feeder and would never refresh — emitting no claim (fail closed). "
                    + "Add the '{}' IdP mapper to the broker's mappers", ProjectEntitlementIdpMapper.PROVIDER_ID);
            return false;
        }
        for (IdentityProviderMapperModel feeder : feeders) {
            IdentityProviderModel idp = realm.getIdentityProviderByAlias(feeder.getIdentityProviderAlias());
            if (idp == null) {
                log.error("Entitlement fetch mapper '{}' references the IdP alias '{}' which does not exist — emitting no claim (fail closed)",
                        feeder.getName(), feeder.getIdentityProviderAlias());
                return false;
            }
            IdentityProviderSyncMode idpMode = idp.getSyncMode() == null
                    ? IdentityProviderSyncMode.LEGACY  // the login path's own null rule (verified against the delegate)
                    : idp.getSyncMode();
            IdentityProviderSyncMode effective = IdentityProviderMapperSyncModeDelegate
                    .combineIdpAndMapperSyncMode(idpMode, feeder.getSyncMode());
            if (effective == IdentityProviderSyncMode.IMPORT) {
                log.error("Entitlement fetch mapper '{}' on IdP '{}' is effectively at sync mode IMPORT — the entitlement cache would freeze after the first login — emitting no claim (fail closed). "
                        + "Configure the mapper at sync mode FORCE (or INHERIT over a FORCE or LEGACY federation)",
                        feeder.getName(), feeder.getIdentityProviderAlias());
                return false;
            }
        }
        return true;
    }

    /**
     * Null-safe lookup of the attribute's User Profile declaration — a
     * malformed (attribute-less) profile config reads as undeclared.
     */
    private static UPAttribute attributeDeclaration(UPConfig profileConfig, String attribute) {
        if (profileConfig == null || profileConfig.getAttributes() == null) {
            return null;
        }
        return profileConfig.getAttribute(attribute);
    }

    private static RealmModel realmOf(KeycloakSession keycloakSession) {
        if (keycloakSession == null) {
            return null;
        }
        KeycloakContext context = keycloakSession.getContext();
        return context == null ? null : context.getRealm();
    }

    /**
     * The freshness half of the cache premise: with the {@code entitlement.max-age}
     * bound enabled (T &gt; 0), the cache counts as present only when its
     * {@code projectEntitlementAt} timestamp exists and is younger than T — a missing,
     * unparsible, or too-old timestamp all treat the cache as absent (fail closed).
     */
    private boolean entitlementCacheIsFresh(UserModel user, ProjectEntitlementConfiguration config) {
        String timestamp = user.getFirstAttribute(ProjectEntitlementConfiguration.ENTITLEMENT_TIMESTAMP_ATTRIBUTE);
        Long fetchedAt = null;
        if (timestamp != null) {
            try {
                fetchedAt = Long.parseLong(timestamp.trim());
            } catch (NumberFormatException e) {
                log.warn("Cached project entitlement timestamp is unparsable — treating the cache as absent (fail closed)");
            }
        }
        if (fetchedAt == null) {
            log.warn("Cached project entitlement has no fetch timestamp and the freshness bound is {} minutes — treating the cache as absent (fail closed); one re-login refreshes it", config.getEntitlementMaxAgeMinutes());
            return false;
        }
        long ageMillis = System.currentTimeMillis() - fetchedAt;
        if (ageMillis > config.getEntitlementMaxAgeMinutes() * 60_000L) {
            log.warn("Cached project entitlement is {} min old, beyond the {} min freshness bound — treating the cache as absent; one re-login refreshes it",
                    ageMillis / 60_000L, config.getEntitlementMaxAgeMinutes());
            return false;
        }
        return true;
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
