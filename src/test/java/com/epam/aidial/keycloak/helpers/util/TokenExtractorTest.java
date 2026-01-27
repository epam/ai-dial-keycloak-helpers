package com.epam.aidial.keycloak.helpers.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TokenExtractorTest {

    private final TokenExtractor tokenExtractor = new TokenExtractor();

    @Test(expected = IllegalArgumentException.class)
    public void extractAccessTokenThrowsWhenTokenDataIsNull() {
        tokenExtractor.extractAccessToken(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void extractAccessTokenThrowsWhenTokenDataIsEmpty() {
        tokenExtractor.extractAccessToken("");
    }

    @Test
    public void extractAccessTokenReturnsJwtAsIsWhenAlreadyPlainToken() {
        String jwt = "eyJ.some.token";

        String result = tokenExtractor.extractAccessToken(jwt);

        assertEquals(jwt, result);
    }

    @Test
    public void extractAccessTokenExtractsAccessTokenFromJson() {
        String json = "{\"access_token\":\"token-value\"}";

        String result = tokenExtractor.extractAccessToken(json);

        assertEquals("token-value", result);
    }

    @Test(expected = RuntimeException.class)
    public void extractAccessTokenThrowsWhenAccessTokenMissingInJson() {
        String json = "{\"other_field\":\"value\"}";

        tokenExtractor.extractAccessToken(json);
    }
}

