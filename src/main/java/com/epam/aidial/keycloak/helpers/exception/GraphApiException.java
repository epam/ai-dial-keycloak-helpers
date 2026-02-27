package com.epam.aidial.keycloak.helpers.exception;

public class GraphApiException extends RuntimeException {

    public GraphApiException(String message) {
        super(message);
    }

    public GraphApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
