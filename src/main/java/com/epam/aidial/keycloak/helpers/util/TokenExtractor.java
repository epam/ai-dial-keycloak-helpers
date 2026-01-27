package com.epam.aidial.keycloak.helpers.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Utility to extract access tokens from Keycloak's stored token data.
 * Keycloak stores the full OAuth response as JSON, not just the access_token.
 */
@Slf4j
public class TokenExtractor {

    private static final String CLAIM_ACCESS_TOKEN = "access_token";

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Extract access_token from Keycloak's stored token data.
     *
     * @param tokenData Raw token data from federatedIdentity.getToken()
     * @return The actual access token string
     */
    public String extractAccessToken(String tokenData) {
        if (tokenData == null || tokenData.isEmpty()) {
            throw new IllegalArgumentException("Token data is null or empty");
        }

        if (tokenData.startsWith("eyJ")) {
            log.debug("Token is already a plain JWT");
            return tokenData;
        }

        try {
            JsonNode json = mapper.readTree(tokenData);
            JsonNode accessTokenNode = json.get(CLAIM_ACCESS_TOKEN);

            if (accessTokenNode == null || accessTokenNode.isNull()) {
                throw new IllegalArgumentException("No access_token found in token data");
            }

            log.debug("Extracted access_token from JSON token data");
            return accessTokenNode.asText();

        } catch (Exception e) {
            log.error("Failed to extract access_token from token data");
            throw new RuntimeException("Failed to extract access_token", e);
        }
    }
}
