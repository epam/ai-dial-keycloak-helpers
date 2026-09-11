package com.epam.aidial.keycloak.helpers.idp;

import com.epam.aidial.keycloak.helpers.model.ProjectGroup;
import com.epam.aidial.keycloak.helpers.provider.GraphFetchException;
import com.epam.aidial.keycloak.helpers.provider.MsGraphProjectGroupsProvider;
import com.epam.aidial.keycloak.helpers.util.TokenExtractor;
import org.junit.Before;
import org.junit.Test;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.UserModel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The lasting/temporary failure split with the fetch timestamp (amended
 * 2026-09-11): a LASTING failure — a missing/unparsible stored broker token,
 * Graph 400/401/403, an invalid convention regex — clears the cache to {@code []}
 * and writes the timestamp; a TEMPORARY one — network errors, 5xx, 429 — keeps the
 * previous entitlement and touches nothing. Every case also pins the timestamp
 * write on success (collaborators injected — constructor injection per the review).
 */
public class ProjectEntitlementIdpMapperTest {

    private static final String ENTITLEMENT_ATTRIBUTE = "projectEntitlement";
    private static final String TIMESTAMP_ATTRIBUTE = "projectEntitlementAt";

    private TokenExtractor tokenExtractor;
    private MsGraphProjectGroupsProvider graphProvider;
    private UserModel user;
    private IdentityProviderMapperModel mapperModel;
    private BrokeredIdentityContext context;
    private ProjectEntitlementIdpMapper mapper;

    @Before
    public void setUp() {
        tokenExtractor = mock(TokenExtractor.class);
        graphProvider = mock(MsGraphProjectGroupsProvider.class);
        mapper = new ProjectEntitlementIdpMapper(tokenExtractor, graphProvider);

        user = mock(UserModel.class);
        when(user.getUsername()).thenReturn("user-a");

        Map<String, String> config = new HashMap<>();
        config.put("convention.regex", "^Project [A-Za-z0-9]+-[A-Za-z0-9]+$");
        config.put("convention.prefix", "Project ");
        mapperModel = mock(IdentityProviderMapperModel.class);
        when(mapperModel.getConfig()).thenReturn(config);

        context = mock(BrokeredIdentityContext.class);
        when(context.getToken()).thenReturn("token-json");
    }

    private void stubSuccessfulFetch() {
        when(tokenExtractor.extractAccessToken("token-json")).thenReturn("access-token");
        when(graphProvider.fetchProjectGroups(eq("access-token"), anyString()))
                .thenReturn(List.of(new ProjectGroup("id-1", "Project EPM-AEM")));
    }

    @Test
    public void successfulFetchCachesEntitlementAndTimestamp() {
        stubSuccessfulFetch();

        mapper.updateBrokeredUser(null, null, user, mapperModel, context);

        verify(user).setSingleAttribute(ENTITLEMENT_ATTRIBUTE, "[\"EPM-AEM\"]");
        verify(user).setSingleAttribute(eq(TIMESTAMP_ATTRIBUTE), anyString());
    }

    @Test
    public void graph400ClearsEntitlementToEmptyList() {
        lastingFailureClears(new GraphFetchException("HTTP 400", 400));
    }

    @Test
    public void graph401ClearsEntitlementToEmptyList() {
        lastingFailureClears(new GraphFetchException("HTTP 401", 401));
    }

    @Test
    public void graph403ClearsEntitlementToEmptyList() {
        lastingFailureClears(new GraphFetchException("HTTP 403", 403));
    }

    private void lastingFailureClears(GraphFetchException failure) {
        when(tokenExtractor.extractAccessToken("token-json")).thenReturn("access-token");
        when(graphProvider.fetchProjectGroups(eq("access-token"), anyString())).thenThrow(failure);

        mapper.updateBrokeredUser(null, null, user, mapperModel, context);

        verify(user).setSingleAttribute(ENTITLEMENT_ATTRIBUTE, "[]");
        verify(user).setSingleAttribute(eq(TIMESTAMP_ATTRIBUTE), anyString()); // a fresh empty list is still fresh
    }

    @Test
    public void missingStoredBrokerTokenClearsEntitlementToEmptyList() {
        // extractAccessToken throws on null/empty/missing-token data
        when(tokenExtractor.extractAccessToken(any())).thenThrow(new IllegalArgumentException("Token data is null or empty"));
        when(context.getToken()).thenReturn(null);

        mapper.updateBrokeredUser(null, null, user, mapperModel, context);

        verify(user).setSingleAttribute(ENTITLEMENT_ATTRIBUTE, "[]");
        verify(user).setSingleAttribute(eq(TIMESTAMP_ATTRIBUTE), anyString());
        verify(graphProvider, never()).fetchProjectGroups(anyString(), anyString());
    }

    @Test
    public void unparsibleStoredBrokerTokenClearsEntitlementToEmptyList() {
        when(tokenExtractor.extractAccessToken(any())).thenThrow(new RuntimeException("Failed to extract access_token"));
        when(context.getToken()).thenReturn("not-json");

        mapper.updateBrokeredUser(null, null, user, mapperModel, context);

        verify(user).setSingleAttribute(ENTITLEMENT_ATTRIBUTE, "[]");
        verify(user).setSingleAttribute(eq(TIMESTAMP_ATTRIBUTE), anyString());
    }

    @Test
    public void invalidConventionRegexClearsEntitlementToEmptyList() {
        when(tokenExtractor.extractAccessToken("token-json")).thenReturn("access-token");
        when(graphProvider.fetchProjectGroups(eq("access-token"), anyString())).thenReturn(List.of());
        when(mapperModel.getConfig()).thenReturn(Map.of(
                "convention.regex", "^Project [unclosed", // PatternSyntaxException at resolution
                "convention.prefix", "Project "));

        mapper.updateBrokeredUser(null, null, user, mapperModel, context);

        verify(user).setSingleAttribute(ENTITLEMENT_ATTRIBUTE, "[]");
        verify(user).setSingleAttribute(eq(TIMESTAMP_ATTRIBUTE), anyString());
    }

    @Test
    public void networkErrorKeepsPreviousEntitlement() {
        temporaryFailureKeeps(new GraphFetchException("network error", 0,
                new java.io.IOException("connection refused")));
    }

    @Test
    public void graph500KeepsPreviousEntitlement() {
        temporaryFailureKeeps(new GraphFetchException("HTTP 503", 503));
    }

    @Test
    public void graph429KeepsPreviousEntitlement() {
        temporaryFailureKeeps(new GraphFetchException("HTTP 429", 429));
    }

    private void temporaryFailureKeeps(GraphFetchException failure) {
        when(tokenExtractor.extractAccessToken("token-json")).thenReturn("access-token");
        when(graphProvider.fetchProjectGroups(eq("access-token"), anyString())).thenThrow(failure);

        mapper.updateBrokeredUser(null, null, user, mapperModel, context);

        verify(user, never()).setSingleAttribute(eq(ENTITLEMENT_ATTRIBUTE), anyString());
        verify(user, never()).setSingleAttribute(eq(TIMESTAMP_ATTRIBUTE), anyString());
    }

    @Test
    public void importNewUserFetchesAndCachesEqually() {
        stubSuccessfulFetch();

        mapper.importNewUser(null, null, user, mapperModel, context);

        verify(user).setSingleAttribute(ENTITLEMENT_ATTRIBUTE, "[\"EPM-AEM\"]");
        verify(user).setSingleAttribute(eq(TIMESTAMP_ATTRIBUTE), anyString());
    }
}
