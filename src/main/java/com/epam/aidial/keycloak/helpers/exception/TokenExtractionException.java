package com.epam.aidial.keycloak.helpers.exception;

public class TokenExtractionException extends RuntimeException {

    public TokenExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
