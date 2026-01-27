package com.epam.aidial.keycloak.helpers.provider;

import com.epam.aidial.keycloak.helpers.model.IdpType;
import com.epam.aidial.keycloak.helpers.model.UserAttributes;

/**
 * Interface for fetching user attributes data from external Identity Providers
 */
public interface UserAttributesProvider {

    /**
     * Fetch user attributes from external IdP
     *
     * @param accessToken External IdP access token
     *
     * @return User attributes data
     */
    UserAttributes getUserAttributes(String accessToken);

    /**
     * Fetch photo as base64 encoded string
     *
     * @param accessToken External IdP access token
     *
     * @return Base64 data URI (e.g., "data:image/jpeg;base64,/9j/4AAQ...")
     */
    default String fetchPhotoAsBase64(String accessToken) {
        return null;
    }

    /**
     * Get the IdP type this provider supports
     *
     * @return IdP type (e.g., "azure", "google", "github")
     */
    IdpType getIdpType();
}
