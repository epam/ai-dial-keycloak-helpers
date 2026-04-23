package com.epam.aidial.keycloak.helpers.provider;

import com.epam.aidial.keycloak.helpers.model.IdpType;
import lombok.extern.slf4j.Slf4j;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Factory for creating IdP-specific user attributes providers
 */
@Slf4j
public class UserAttributesProviderFactory {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Map<IdpType, UserAttributesProvider> providers;

    public UserAttributesProviderFactory() {
        this.providers = new EnumMap<>(IdpType.class);
        registerDefaultProviders();
    }

    private void registerDefaultProviders() {
        // Add more providers here
        registerProvider(new MsGraphUserAttributesProvider(HTTP_CLIENT));
    }

    private void registerProvider(UserAttributesProvider provider) {
        providers.put(provider.getIdpType(), provider);
        log.info("Registered IdP provider: {}", provider.getIdpType());
    }

    public UserAttributesProvider getProvider(IdpType idpType) {
        if (idpType == null) {
            return null;
        }
        return providers.get(idpType);
    }
}
