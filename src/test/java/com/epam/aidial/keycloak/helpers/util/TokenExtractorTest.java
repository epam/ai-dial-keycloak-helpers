package com.epam.aidial.keycloak.helpers.util;

import com.epam.aidial.keycloak.helpers.exception.TokenExtractionException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TokenExtractorTest {

    @Test(expected = IllegalArgumentException.class)
    public void extractAccessTokenThrowsWhenTokenDataIsNull() {
        TokenExtractor.extractAccessToken(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void extractAccessTokenThrowsWhenTokenDataIsEmpty() {
        TokenExtractor.extractAccessToken("");
    }

    @Test
    public void extractAccessTokenReturnsJwtAsIsWhenAlreadyPlainToken() {
        String jwt = "eyJ.some.token";

        String result = TokenExtractor.extractAccessToken(jwt);

        assertEquals(jwt, result);
    }

    @Test
    public void extractAccessTokenExtractsAccessTokenFromJson() {
        String json = "{\"access_token\":\"token-value\"}";

        String result = TokenExtractor.extractAccessToken(json);

        assertEquals("token-value", result);
    }

    @Test(expected = TokenExtractionException.class)
    public void extractAccessTokenThrowsWhenAccessTokenMissingInJson() {
        String json = "{\"other_field\":\"value\"}";

        TokenExtractor.extractAccessToken(json);
    }
}
