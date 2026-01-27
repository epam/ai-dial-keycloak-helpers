package com.epam.aidial.keycloak.helpers.model;

import lombok.Getter;

/**
 * Supported Identity Provider types that can be enriched by this module.
 */
@Getter
public enum IdpType {

    MICROSOFT,
    GOOGLE,
    GITHUB;
}
