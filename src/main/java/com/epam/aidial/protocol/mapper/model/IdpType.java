package com.epam.aidial.protocol.mapper.model;

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
