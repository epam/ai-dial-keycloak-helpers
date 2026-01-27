package com.epam.aidial.keycloak.helpers.model;

import lombok.Builder;
import lombok.Data;

/**
 * DTO of user attributes fetched from an external IdP API.
 */
@Data
@Builder
public class UserAttributes {
    private String name;
    private String jobTitle;
}
