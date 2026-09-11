package com.epam.aidial.keycloak.helpers.idp;

import com.epam.aidial.keycloak.helpers.config.ProjectEntitlementConfiguration;
import com.epam.aidial.keycloak.helpers.model.ProjectEntitlement;
import com.epam.aidial.keycloak.helpers.model.ProjectGroup;
import com.epam.aidial.keycloak.helpers.provider.GraphFetchException;
import com.epam.aidial.keycloak.helpers.provider.MsGraphProjectGroupsProvider;
import com.epam.aidial.keycloak.helpers.util.TokenExtractor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.broker.provider.AbstractIdentityProviderMapper;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.IdentityProviderSyncMode;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;
import java.util.regex.PatternSyntaxException;

/**
 * Identity provider mapper that caches the brokered user's <b>project entitlement</b>
 * (D-019, config-repo spec 02 §2 piece 1): at every brokered login it extracts the
 * user's external access token, fetches the user's convention-prefixed Graph groups
 * via {@link MsGraphProjectGroupsProvider}, and caches the resolved entitlement on
 * the Keycloak user as a JSON array under {@code projectEntitlement} — with the
 * {@code projectEntitlementAt} fetch timestamp (epoch-millis) beside it, the input
 * of the protocol mapper's freshness bound (amended 2026-09-11).
 *
 * <p><b>Failure semantics (explicit, never blocks login, never invents data — the
 * lasting/temporary split, amended 2026-09-11)</b>: a <b>lasting</b> failure — a
 * missing or unparsible stored broker token, Graph 400/401/403, an invalid convention
 * regex — membership is unverifiable and will not heal on retry, so the cached list is
 * <b>cleared to {@code []}</b> (+ ERROR log; a fresh empty list is still fresh — the
 * timestamp is written on every clear). A <b>temporary</b> failure — a network error,
 * Graph 5xx, 429 — <b>keeps the previous entitlement</b> (+ WARN log; the timestamp is
 * left untouched). Either way the validate-and-emit protocol mapper emits no claim from
 * an empty/absent cache, and the client's mandatory claim-verification detects the miss
 * downstream.
 */
@Slf4j
public class ProjectEntitlementIdpMapper extends AbstractIdentityProviderMapper {

    public static final String PROVIDER_ID = "entra-project-entitlement-idp-mapper";

    private static final String EMPTY_ENTITLEMENT = "[]";

    private final TokenExtractor tokenExtractor;
    private final MsGraphProjectGroupsProvider graphProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Keycloak instantiates the mapper through the SPI — default collaborators. */
    public ProjectEntitlementIdpMapper() {
        this(new TokenExtractor(), new MsGraphProjectGroupsProvider());
    }

    /** Collaborator injection for tests (the reviewer's constructor-injection suggestion). */
    ProjectEntitlementIdpMapper(TokenExtractor tokenExtractor, MsGraphProjectGroupsProvider graphProvider) {
        this.tokenExtractor = tokenExtractor;
        this.graphProvider = graphProvider;
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    /**
     * IMPORT is excluded (the 2026-09-11 amendment): an effectively-IMPORT setup
     * would freeze the cache after the first login, silently. Keycloak's own
     * brokered-login delegate warns at login for such setups; the protocol
     * mapper's sync guard is the enforcement — it refuses to mint from a frozen
     * entitlement (fail closed at the mint).
     */
    @Override
    public boolean supportsSyncMode(IdentityProviderSyncMode syncMode) {
        return syncMode == IdentityProviderSyncMode.FORCE || syncMode == IdentityProviderSyncMode.LEGACY;
    }

    @Override
    public String[] getCompatibleProviders() {
        return new String[] { "microsoft", "oidc" };
    }

    @Override
    public String getDisplayCategory() {
        return "External API Enrichment";
    }

    @Override
    public String getDisplayType() {
        return "Entra Project Entitlement";
    }

    @Override
    public String getHelpText() {
        return "Fetches the user's convention-matching project groups from Microsoft Graph at brokered login "
                + "using the user's own delegated token, and caches the entitlement for the "
                + "project-selection protocol mapper";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return ProjectEntitlementConfiguration.getConfigProperties();
    }

    @Override
    public void importNewUser(KeycloakSession session, RealmModel realm,
                              UserModel user, IdentityProviderMapperModel mapperModel,
                              BrokeredIdentityContext context) {
        fetchAndCache(user, mapperModel, context);
    }

    @Override
    public void updateBrokeredUser(KeycloakSession session, RealmModel realm,
                                   UserModel user, IdentityProviderMapperModel mapperModel,
                                   BrokeredIdentityContext context) {
        fetchAndCache(user, mapperModel, context);
    }

    private void fetchAndCache(UserModel user, IdentityProviderMapperModel mapperModel,
                               BrokeredIdentityContext context) {
        ProjectEntitlementConfiguration config = ProjectEntitlementConfiguration.fromModel(mapperModel);

        // A missing or unparsible stored broker token is a LASTING failure — the
        // entitlement is unverifiable now and at every future login until a real
        // re-login stores a fresh token; keep nothing.
        String accessToken;
        try {
            accessToken = tokenExtractor.extractAccessToken(context.getToken());
        } catch (RuntimeException e) {
            log.error("No usable broker token for user {} — clearing the cached project entitlement (lasting failure)", user.getUsername(), e);
            clearEntitlement(user, config);
            return;
        }
        if (accessToken == null) {
            log.error("No usable broker token for user {} — clearing the cached project entitlement (lasting failure)", user.getUsername());
            clearEntitlement(user, config);
            return;
        }

        try {
            List<ProjectGroup> groups = graphProvider.fetchProjectGroups(accessToken, config.getConventionPrefix(),
                    config.getGraphConnectTimeoutMillis(), config.getGraphReadTimeoutMillis());
            ProjectEntitlement entitlement = ProjectEntitlement.fromGroups(
                    groups, config.getConventionRegex(), config.getConventionPrefix());

            user.setSingleAttribute(config.getEntitlementAttribute(),
                    objectMapper.writeValueAsString(entitlement.getValues()));
            user.setSingleAttribute(ProjectEntitlementConfiguration.ENTITLEMENT_TIMESTAMP_ATTRIBUTE,
                    Long.toString(System.currentTimeMillis()));
            log.debug("Cached project entitlement ({} projects) for user {}",
                    entitlement.getValues().size(), user.getUsername());

        } catch (GraphFetchException e) {
            if (e.isLasting()) {
                log.error("Project entitlement fetch failed for user {} (HTTP {}) — clearing the cached entitlement (lasting failure)",
                        user.getUsername(), e.getStatusCode(), e);
                clearEntitlement(user, config);
            } else {
                log.warn("Project entitlement fetch failed for user {} — keeping previous entitlement if present (temporary failure)",
                        user.getUsername(), e);
            }
        } catch (PatternSyntaxException e) {
            // An invalid convention regex is a mapper-configuration defect — it fails
            // at every login until fixed; nothing resolvable remains to keep.
            log.error("Invalid project convention regex — clearing the cached project entitlement for user {} (lasting failure)", user.getUsername(), e);
            clearEntitlement(user, config);
        } catch (JsonProcessingException e) {
            // Serialization of a plain string list cannot fail in practice — kept as the
            // safe fallback: never invent data, never block login, touch nothing stale.
            log.warn("Failed to serialize the project entitlement for user {} — keeping previous entitlement if present", user.getUsername(), e);
        }
    }

    /** The lasting-failure landing: an empty cache (still fresh — the timestamp is written). */
    private void clearEntitlement(UserModel user, ProjectEntitlementConfiguration config) {
        user.setSingleAttribute(config.getEntitlementAttribute(), EMPTY_ENTITLEMENT);
        user.setSingleAttribute(ProjectEntitlementConfiguration.ENTITLEMENT_TIMESTAMP_ATTRIBUTE,
                Long.toString(System.currentTimeMillis()));
    }
}
