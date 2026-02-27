package com.epam.aidial.keycloak.helpers.idp;

import com.epam.aidial.keycloak.helpers.config.MapperConfiguration;
import com.epam.aidial.keycloak.helpers.model.IdpType;
import com.epam.aidial.keycloak.helpers.model.UserAttributes;
import com.epam.aidial.keycloak.helpers.provider.UserAttributesProvider;
import com.epam.aidial.keycloak.helpers.provider.UserAttributesProviderFactory;
import com.epam.aidial.keycloak.helpers.util.TokenExtractor;
import lombok.RequiredArgsConstructor;
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
 * Base identity provider mapper that enriches the Keycloak user with attributes fetched from an external IdP API.
 *
 * <p>The access token is extracted from brokered identity context and used to call an {@link UserAttributesProvider}.
 * Results are stored as user attributes and can later be mapped into tokens by protocol mappers.
 */
@Slf4j
@RequiredArgsConstructor
public abstract class UserAttributesIdpMapper extends AbstractIdentityProviderMapper  {

    private final UserAttributesProviderFactory userAttributesProviderFactory = new UserAttributesProviderFactory();

    /**
     * Returns configuration properties supported by this mapper.
     *
     * @return list of config properties
     */
    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return MapperConfiguration.getConfigProperties();
    }

    @Override
    public void importNewUser(KeycloakSession session, RealmModel realm,
                              UserModel user, IdentityProviderMapperModel mapperModel,
                              BrokeredIdentityContext context) {
        fetchAndStore(user, mapperModel, context);
    }

    @Override
    public void updateBrokeredUser(KeycloakSession session, RealmModel realm,
                                   UserModel user, IdentityProviderMapperModel mapperModel,
                                   BrokeredIdentityContext context) {
        fetchAndStore(user, mapperModel, context);
    }

    private void fetchAndStore(UserModel user, IdentityProviderMapperModel mapperModel,
                               BrokeredIdentityContext context) {
        String tokenData = context.getToken();
        String accessToken = TokenExtractor.extractAccessToken(tokenData);

        if (accessToken == null) {
            log.warn("No broker token available for user {}", user.getUsername());
            return;
        }

        MapperConfiguration config = MapperConfiguration.fromModel(mapperModel);
        UserAttributesProvider userAttributesProvider = userAttributesProviderFactory.getProvider(getIdpType());

        try {
            if (config.isFetchJobTitle()) {
                UserAttributes userAttributes = userAttributesProvider.getUserAttributes(accessToken);
                setAttributeIfPresent(user, "jobTitle", userAttributes.getJobTitle());
            }

            if (config.isFetchPhoto()) {
                String photo = userAttributesProvider.fetchPhotoAsBase64(accessToken);
                setAttributeIfPresent(user, "picture", photo);
            }

        } catch (Exception e) {
            log.error("Failed to fetch User attributes data for user {}", user.getUsername(), e);
        }
    }

    private void setAttributeIfPresent(UserModel user, String attribute, String value) {
        if (value != null && !value.isEmpty()) {
            user.setSingleAttribute(attribute, value);
            log.debug("Set attribute {}={} for user {}", attribute, value, user.getUsername());
        }
    }

    /**
     * Returns the identity provider type this mapper instance supports.
     *
     * @return IdP type
     */
    abstract IdpType getIdpType();
}
