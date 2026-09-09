package com.epam.aidial.keycloak.helpers.idp;

import com.epam.aidial.keycloak.helpers.config.ProjectEntitlementConfiguration;
import com.epam.aidial.keycloak.helpers.model.ProjectEntitlement;
import com.epam.aidial.keycloak.helpers.model.ProjectGroup;
import com.epam.aidial.keycloak.helpers.provider.MsGraphProjectGroupsProvider;
import com.epam.aidial.keycloak.helpers.util.TokenExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.broker.provider.AbstractIdentityProviderMapper;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

/**
 * Identity provider mapper that caches the brokered user's <b>project entitlement</b>
 * (D-019, config-repo spec 02 §2 piece 1): at every brokered login it extracts the
 * user's external access token, fetches the user's convention-prefixed Graph groups
 * via {@link MsGraphProjectGroupsProvider}, and caches the resolved entitlement on
 * the Keycloak user as a JSON array under {@code projectEntitlement}.
 *
 * <p><b>Failure semantics (explicit, never blocks login, never invents data)</b>: a
 * Graph error leaves the user's previous entitlement in place if one exists, else
 * sets an empty one — either way the validate-and-emit protocol mapper emits no
 * claim, and the client's mandatory claim-verification detects the miss downstream.
 */
@Slf4j
public class ProjectEntitlementIdpMapper extends AbstractIdentityProviderMapper {

    public static final String PROVIDER_ID = "entra-project-entitlement-idp-mapper";

    private static final String EMPTY_ENTITLEMENT = "[]";

    private final TokenExtractor tokenExtractor = new TokenExtractor();
    private final MsGraphProjectGroupsProvider graphProvider = new MsGraphProjectGroupsProvider();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getId() {
        return PROVIDER_ID;
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
        try {
            String accessToken = tokenExtractor.extractAccessToken(context.getToken());
            if (accessToken == null) {
                log.warn("No broker token available for user {} — entitlement not refreshed", user.getUsername());
                onFetchFailed(user, config);
                return;
            }

            List<ProjectGroup> groups = graphProvider.fetchProjectGroups(accessToken, config.getConventionPrefix());
            ProjectEntitlement entitlement = ProjectEntitlement.fromGroups(
                    groups, config.getConventionRegex(), config.getConventionPrefix());

            user.setSingleAttribute(config.getEntitlementAttribute(),
                    objectMapper.writeValueAsString(entitlement.getValues()));
            log.debug("Cached project entitlement ({}, {} projects) for user {}",
                    entitlement.getMode(), entitlement.getValues().size(), user.getUsername());

        } catch (Exception e) {
            log.warn("Project entitlement fetch failed for user {} — keeping previous entitlement if present",
                    user.getUsername(), e);
            onFetchFailed(user, config);
        }
    }

    private void onFetchFailed(UserModel user, ProjectEntitlementConfiguration config) {
        if (user.getFirstAttribute(config.getEntitlementAttribute()) == null) {
            user.setSingleAttribute(config.getEntitlementAttribute(), EMPTY_ENTITLEMENT);
        }
    }
}
