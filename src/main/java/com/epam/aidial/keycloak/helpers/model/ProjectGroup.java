package com.epam.aidial.keycloak.helpers.model;

import lombok.Value;

/**
 * A Microsoft Graph directory group as returned by {@code /me/memberOf/microsoft.graph.group}.
 *
 * <p>{@code displayName} is {@code null} when the caller may not read it (Graph's documented
 * "limited information" serialization under delegated {@code User.Read} without
 * {@code GroupMember.Read.All}) — the D-019 degraded mode.
 */
@Value
public class ProjectGroup {

    String id;

    String displayName;
}
