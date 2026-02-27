package com.epam.aidial.keycloak.helpers.protocol;

import com.epam.aidial.keycloak.helpers.config.MapperConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.protocol.oidc.mappers.UserInfoTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

import java.util.ArrayList;
import java.util.List;

/**
 * Protocol mapper that maps previously cached user attributes (stored on the {@link UserModel})
 * into OIDC token claims.
 *
 * <p>This mapper does not call external IdPs directly. It only reads attributes saved during login
 * by an identity provider mapper (e.g. {@code UserAttributesIdpMapper} implementations).
 */
@Slf4j
public class CachedUserAttributesProtocolMapper extends AbstractOIDCProtocolMapper
        implements OIDCAccessTokenMapper, UserInfoTokenMapper {

    public static final String PROVIDER_ID = "cached-user-attributes-protocol-mapper";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Cached User Attributes (OIDC Claims)";
    }

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;
    }

    @Override
    public String getHelpText() {
        return "Maps cached user attributes from external idp to token claims";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        List<ProviderConfigProperty> properties = new ArrayList<>(MapperConfiguration.getConfigProperties());
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(properties, CachedUserAttributesProtocolMapper.class);
        return properties;
    }

    @Override
    protected void setClaim(IDToken token, ProtocolMapperModel mappingModel,
                            UserSessionModel userSession, KeycloakSession keycloakSession,
                            ClientSessionContext clientSessionCtx) {

        UserModel user = userSession.getUser();
        MapperConfiguration config = MapperConfiguration.fromModel(mappingModel);

        if (config.isFetchJobTitle()) {
            addClaimIfPresent(token, user, "jobTitle", "job_title");
        }

        if (config.isFetchPhoto()) {
            addClaimIfPresent(token, user, "picture", "picture");
        }
    }

    private void addClaimIfPresent(IDToken token, UserModel user,
                                   String attribute, String claim) {
        List<String> values = user.getAttributeStream(attribute).toList();
        if (values.size() > 1) {
            throw new IllegalStateException(
                    "Expected single value for attribute '" + attribute
                            + "' but found " + values.size() + " for user " + user.getUsername());
        }
        if (!values.isEmpty()) {
            token.getOtherClaims().put(claim, values.get(0));
            log.debug("Set claim {}={} for user {}", claim, values.get(0), user.getUsername());
        }
    }
}