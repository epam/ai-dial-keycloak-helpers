package com.epam.aidial.keycloak.helpers.provider;

/**
 * A failed Microsoft Graph fetch by the project-groups provider, carrying the
 * HTTP status for the caller's lasting/temporary failure split (amended
 * 2026-09-11): a <b>lasting</b> failure — Graph 400/401/403 (a configuration or
 * authorization defect that will not heal on retry) — must clear the cached
 * entitlement; a <b>temporary</b> one — network errors, 5xx, 429, a pagination
 * anomaly — must keep it. Any status other than the lasting set (including no
 * status at all) classifies temporary.
 */
public class GraphFetchException extends RuntimeException {

    private final int statusCode;

    public GraphFetchException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public GraphFetchException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** The Graph HTTP status, or {@code 0} when no status applies (e.g. a network error). */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * A lasting failure — membership is unverifiable and will not heal on
     * retry: Graph rejected the request outright (400/401/403).
     */
    public boolean isLasting() {
        return statusCode == 400 || statusCode == 401 || statusCode == 403;
    }
}
