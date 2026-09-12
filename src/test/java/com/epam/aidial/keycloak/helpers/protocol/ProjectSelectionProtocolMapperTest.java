package com.epam.aidial.keycloak.helpers.protocol;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.Test;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.IdentityProviderMapperSyncMode;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.IdentityProviderSyncMode;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.IDToken;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPAttributePermissions;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.keycloak.userprofile.UserProfileProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Request-carried selection contract (config-repo spec 02 §4, amended
 * 2026-09-10): the mint-side selection source is the REQUEST's {@code project}
 * form parameter — never IdP session state. No param → no claim, uniformly;
 * the mapper emits only when the request's selection ∈ the cached entitlement.
 *
 * <p>The fixture realm satisfies the mapper's premise guards (amended
 * 2026-09-11): the entitlement attribute is declared admin-only in the User
 * Profile, and the realm holds a healthy entitlement fetch-mapper feeder —
 * the dedicated guard cases override one premise at a time.
 */
public class ProjectSelectionProtocolMapperTest {

    private static final String ENTITLED = "[\"abc-42\",\"xyz-9\"]";
    private static final String ENTITLEMENT_ATTRIBUTE = "projectEntitlement";
    private static final String TIMESTAMP_ATTRIBUTE = "projectEntitlementAt";

    private final ProjectSelectionProtocolMapper mapper = new ProjectSelectionProtocolMapper();

    private ProtocolMapperModel mappingModel() {
        ProtocolMapperModel mappingModel = mock(ProtocolMapperModel.class);
        Map<String, String> config = new HashMap<>();
        config.put("claim.name", "project");
        when(mappingModel.getConfig()).thenReturn(config);
        return mappingModel;
    }

    private UserSessionModel userSessionWithEntitlement(String entitlementJson) {
        return userSessionWith(entitlementJson, null);
    }

    private UserSessionModel userSessionWith(String entitlementJson, String fetchedAt) {
        UserModel user = mock(UserModel.class);
        when(user.getFirstAttribute(ENTITLEMENT_ATTRIBUTE)).thenReturn(entitlementJson);
        when(user.getFirstAttribute(TIMESTAMP_ATTRIBUTE)).thenReturn(fetchedAt);
        UserSessionModel userSession = mock(UserSessionModel.class);
        when(userSession.getUser()).thenReturn(user);
        return userSession;
    }

    private ProtocolMapperModel mappingModelWithMaxAge(String maxAgeMinutes) {
        ProtocolMapperModel mappingModel = mappingModel();
        mappingModel.getConfig().put("entitlement.max-age", maxAgeMinutes);
        return mappingModel;
    }

    /**
     * The User Profile declaration the attribute-premise guard requires: BOTH
     * halves of the cached state — the entitlement attribute AND its fetch
     * timestamp — declared admin-only (edit: admin) — the reference realm
     * configuration, and the adopting realm's checklist item.
     */
    private static UPConfig adminOnlyProfile() {
        UPConfig profile = new UPConfig();
        profile.addOrReplaceAttribute(new UPAttribute(ENTITLEMENT_ATTRIBUTE,
                new UPAttributePermissions(Set.of(), Set.of("admin"))));
        profile.addOrReplaceAttribute(new UPAttribute(TIMESTAMP_ATTRIBUTE,
                new UPAttributePermissions(Set.of(), Set.of("admin"))));
        return profile;
    }

    /**
     * A KeycloakSession whose context carries an HTTP request with the given
     * form parameters — the mint-side selection source (the exchange/refresh
     * POST body as the token endpoint decoded it) — plus a realm whose User
     * Profile declares the entitlement attribute admin-only (the attribute-
     * premise guard's happy path).
     */
    private KeycloakSession sessionWithFormParams(MultivaluedMap<String, String> formParams) {
        return sessionWith(formParams, adminOnlyProfile());
    }

    private KeycloakSession sessionWith(MultivaluedMap<String, String> formParams, UPConfig profileConfig) {
        // The sync guard's happy path: one feeder mapper at FORCE over a FORCE
        // federation (the reference realm configuration).
        return sessionWith(formParams, profileConfig,
                List.of(feederAt(IdentityProviderMapperSyncMode.FORCE)), Map.of("entra", idpAt(IdentityProviderSyncMode.FORCE)));
    }

    private KeycloakSession sessionWith(MultivaluedMap<String, String> formParams, UPConfig profileConfig,
                                        List<IdentityProviderMapperModel> feeders, Map<String, IdentityProviderModel> idpsByAlias) {
        HttpRequest request = mock(HttpRequest.class);
        when(request.getDecodedFormParameters()).thenReturn(formParams);
        RealmModel realm = mock(RealmModel.class);
        when(realm.getIdentityProviderMappersStream()).thenReturn(feeders.stream());
        when(realm.getIdentityProviderByAlias(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> idpsByAlias.get(invocation.getArgument(0, String.class)));
        KeycloakContext context = mock(KeycloakContext.class);
        when(context.getHttpRequest()).thenReturn(request);
        when(context.getRealm()).thenReturn(realm);
        UserProfileProvider profileProvider = mock(UserProfileProvider.class);
        when(profileProvider.getConfiguration()).thenReturn(profileConfig);
        KeycloakSession session = mock(KeycloakSession.class);
        when(session.getContext()).thenReturn(context);
        when(session.getProvider(UserProfileProvider.class)).thenReturn(profileProvider);
        return session;
    }

    private KeycloakSession sessionWithFormParam(String name, String value) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        if (value != null) {
            params.add(name, value);
        }
        return sessionWithFormParams(params);
    }

    private MultivaluedMap<String, String> selectionParam(String value) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        if (value != null) {
            params.add("project", value);
        }
        return params;
    }

    /** A realm IdP-mapper instance of the entitlement fetch mapper at the given mode. */
    private static IdentityProviderMapperModel feederAt(IdentityProviderMapperSyncMode syncMode) {
        IdentityProviderMapperModel feeder = new IdentityProviderMapperModel();
        feeder.setName("entitlement-feeder");
        feeder.setIdentityProviderAlias("entra");
        feeder.setIdentityProviderMapper(com.epam.aidial.keycloak.helpers.idp.ProjectEntitlementIdpMapper.PROVIDER_ID);
        feeder.setConfig(new HashMap<>()); // setSyncMode writes through the config map
        feeder.setSyncMode(syncMode);
        return feeder;
    }

    /** A federation instance at the given sync mode (null = unset — the console can leave it empty). */
    private static IdentityProviderModel idpAt(IdentityProviderSyncMode syncMode) {
        IdentityProviderModel idp = new IdentityProviderModel();
        idp.setAlias("entra");
        idp.setSyncMode(syncMode);
        return idp;
    }

    @Test
    public void requestParamWithinEntitlementEmitsClaim() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                sessionWithFormParam("project", "abc-42"), mock(ClientSessionContext.class));

        assertEquals("abc-42", token.getOtherClaims().get("project"));
    }

    @Test
    public void unentitledRequestParamSilentlyDropped() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                sessionWithFormParam("project", "NOT-MINE"), mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void absentRequestParamEmitsNothing() {
        AccessToken token = new AccessToken();

        // No param → no claim, uniformly (no fallback, no session state — the
        // 2026-09-10 ruling; a param-less client's tokens stay baseline).
        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                sessionWithFormParam("project", null), mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void absentEntitlementEmitsNothing() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(null),
                sessionWithFormParam("project", "abc-42"), mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void malformedEntitlementFailsClosed() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement("not-json"),
                sessionWithFormParam("project", "abc-42"), mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void nullRequestContextEmitsNothing() {
        AccessToken token = new AccessToken();

        // Guarded: mints outside request scope (service-account/offline paths)
        // read no session state either — they simply emit no claim.
        KeycloakSession noRequestContext = mock(KeycloakSession.class);
        when(noRequestContext.getContext()).thenReturn(null);

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                noRequestContext, mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void nullSessionEmitsNothing() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                null, mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void idTokenNeverReceivesTheClaim() {
        IDToken idToken = new IDToken();

        mapper.setClaim(idToken, mappingModel(), userSessionWithEntitlement(ENTITLED),
                sessionWithFormParam("project", "abc-42"), mock(ClientSessionContext.class));

        assertFalse(idToken.getOtherClaims().containsKey("project"));
    }

    @Test
    public void claimIsSingularStringEvenForMultiProjectEntitlement() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                sessionWithFormParam("project", "abc-42"), mock(ClientSessionContext.class));

        Object claim = token.getOtherClaims().get("project");
        assertTrue(claim instanceof String);
        assertEquals("abc-42", claim);
    }

    @Test
    public void undeclaredEntitlementAttributeEmitsNothing() {
        AccessToken token = new AccessToken();

        // The attribute-premise guard: an attribute absent from the realm's
        // User Profile declaration is user-writable when unmanaged attributes
        // are enabled — the premise of the mint decision is broken → no claim.
        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                sessionWith(selectionParam("abc-42"), new UPConfig()), mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void userEditableEntitlementAttributeEmitsNothing() {
        AccessToken token = new AccessToken();

        // The attribute-premise guard: a user-editable declaration lets the
        // Account API self-write the entitlement — no claim (fail closed).
        UPConfig userEditable = new UPConfig();
        userEditable.addOrReplaceAttribute(new UPAttribute(ENTITLEMENT_ATTRIBUTE,
                new UPAttributePermissions(Set.of(), Set.of("user", "admin"))));
        userEditable.addOrReplaceAttribute(new UPAttribute(TIMESTAMP_ATTRIBUTE,
                new UPAttributePermissions(Set.of(), Set.of("admin"))));

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                sessionWith(selectionParam("abc-42"), userEditable), mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void userEditableTimestampAttributeEmitsNothing() {
        AccessToken token = new AccessToken();

        // The timestamp is the other half of the premise: a user-writable
        // projectEntitlementAt lets an account self-write a far-future fetch
        // time and defeat the freshness bound (the cache would never age out).
        UPConfig userEditableTimestamp = new UPConfig();
        userEditableTimestamp.addOrReplaceAttribute(new UPAttribute(ENTITLEMENT_ATTRIBUTE,
                new UPAttributePermissions(Set.of(), Set.of("admin"))));
        userEditableTimestamp.addOrReplaceAttribute(new UPAttribute(TIMESTAMP_ATTRIBUTE,
                new UPAttributePermissions(Set.of(), Set.of("user"))));

        mapper.setClaim(token, mappingModelWithMaxAge("5"), userSessionWith(ENTITLED, Long.toString(System.currentTimeMillis())),
                sessionWith(selectionParam("abc-42"), userEditableTimestamp), mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void undeclaredTimestampAttributeEmitsNothing() {
        AccessToken token = new AccessToken();

        // Only the entitlement attribute is declared — the timestamp attribute
        // rides the unmanaged-attributes write surface → the premise is broken.
        UPConfig entitlementOnly = new UPConfig();
        entitlementOnly.addOrReplaceAttribute(new UPAttribute(ENTITLEMENT_ATTRIBUTE,
                new UPAttributePermissions(Set.of(), Set.of("admin"))));

        mapper.setClaim(token, mappingModelWithMaxAge("5"), userSessionWith(ENTITLED, Long.toString(System.currentTimeMillis())),
                sessionWith(selectionParam("abc-42"), entitlementOnly), mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void malformedDeclarationWithoutEditKeyEmitsNothing() {
        AccessToken token = new AccessToken();

        // A declaration whose permissions carry no edit key reads as malformed —
        // fail closed, and never an NPE inside the mint.
        UPConfig noEditKey = new UPConfig();
        noEditKey.addOrReplaceAttribute(new UPAttribute(ENTITLEMENT_ATTRIBUTE,
                new UPAttributePermissions(Set.of(), null)));

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                sessionWith(selectionParam("abc-42"), noEditKey), mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void corruptFeederSyncModeEmitsNothingWithoutThrowing() {
        AccessToken token = new AccessToken();

        // A garbage syncMode string would throw inside the model's valueOf —
        // the mint must never 500: no claim, loudly.
        IdentityProviderMapperModel corrupt = feederAt(IdentityProviderMapperSyncMode.FORCE);
        corrupt.getConfig().put("syncMode", "GARBAGE");

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                sessionWith(selectionParam("abc-42"), adminOnlyProfile(),
                        List.of(corrupt), Map.of("entra", idpAt(IdentityProviderSyncMode.FORCE))),
                mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void nullMapperConfigEmitsNothingWithoutThrowing() {
        AccessToken token = new AccessToken();

        ProtocolMapperModel nullConfig = mock(ProtocolMapperModel.class);
        when(nullConfig.getConfig()).thenReturn(null);

        mapper.setClaim(token, nullConfig, userSessionWithEntitlement(ENTITLED),
                sessionWithFormParam("project", "abc-42"), mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void adminOnlyDeclaredAttributeEmits() {
        AccessToken token = new AccessToken();

        // The premise the guard requires — the properly declared admin-only
        // attribute (the reference realm policy) — must not change the emit path.
        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                sessionWithFormParam("project", "abc-42"), mock(ClientSessionContext.class));

        assertEquals("abc-42", token.getOtherClaims().get("project"));
    }

    @Test
    public void freshCacheWithinBoundEmits() {
        AccessToken token = new AccessToken();
        String fresh = Long.toString(System.currentTimeMillis() - 60_000L); // 1 min old

        mapper.setClaim(token, mappingModelWithMaxAge("5"), userSessionWith(ENTITLED, fresh),
                sessionWithFormParam("project", "abc-42"), mock(ClientSessionContext.class));

        assertEquals("abc-42", token.getOtherClaims().get("project"));
    }

    @Test
    public void cacheOlderThanBoundEmitsNothing() {
        AccessToken token = new AccessToken();
        String stale = Long.toString(System.currentTimeMillis() - 10 * 60_000L); // 10 min old, bound 5

        mapper.setClaim(token, mappingModelWithMaxAge("5"), userSessionWith(ENTITLED, stale),
                sessionWithFormParam("project", "abc-42"), mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void missingTimestampWithBoundEmitsNothing() {
        AccessToken token = new AccessToken();

        // Fail closed: with the bound enabled, a cache without a fetch timestamp
        // (e.g. written by a pre-amendment build) is treated as absent.
        mapper.setClaim(token, mappingModelWithMaxAge("5"), userSessionWith(ENTITLED, null),
                sessionWithFormParam("project", "abc-42"), mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void unparsableTimestampWithBoundEmitsNothing() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModelWithMaxAge("5"), userSessionWith(ENTITLED, "not-a-number"),
                sessionWithFormParam("project", "abc-42"), mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void zeroBoundDisablesTheAgeCheck() {
        AccessToken token = new AccessToken();
        String veryOld = "1546300800000"; // 2019 — the bound is disabled; age is never checked

        mapper.setClaim(token, mappingModelWithMaxAge("0"), userSessionWith(ENTITLED, veryOld),
                sessionWithFormParam("project", "abc-42"), mock(ClientSessionContext.class));

        assertEquals("abc-42", token.getOtherClaims().get("project"));
    }

    @Test
    public void boundAbsentFromConfigDisablesTheAgeCheck() {
        AccessToken token = new AccessToken();
        String veryOld = "1546300800000";

        // The default realm (no entitlement.max-age configured) — enforcement inert.
        mapper.setClaim(token, mappingModel(), userSessionWith(ENTITLED, veryOld),
                sessionWithFormParam("project", "abc-42"), mock(ClientSessionContext.class));

        assertEquals("abc-42", token.getOtherClaims().get("project"));
    }

    // ---- the sync guard: the realm must hold a healthy fetch-mapper feeder ----

    @Test
    public void feederAtLegacySyncModeEmits() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                sessionWith(selectionParam("abc-42"), adminOnlyProfile(),
                        List.of(feederAt(IdentityProviderMapperSyncMode.LEGACY)),
                        Map.of("entra", idpAt(IdentityProviderSyncMode.FORCE))),
                mock(ClientSessionContext.class));

        assertEquals("abc-42", token.getOtherClaims().get("project"));
    }

    @Test
    public void inheritOverForceFederationEmits() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                sessionWith(selectionParam("abc-42"), adminOnlyProfile(),
                        List.of(feederAt(IdentityProviderMapperSyncMode.INHERIT)),
                        Map.of("entra", idpAt(IdentityProviderSyncMode.FORCE))),
                mock(ClientSessionContext.class));

        assertEquals("abc-42", token.getOtherClaims().get("project"));
    }

    @Test
    public void inheritOverUnsetFederationResolvesLegacyAndEmits() {
        AccessToken token = new AccessToken();

        // The verified delegate rule: an UNSET federation mode resolves to LEGACY,
        // which refreshes every login — benign, emits.
        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                sessionWith(selectionParam("abc-42"), adminOnlyProfile(),
                        List.of(feederAt(IdentityProviderMapperSyncMode.INHERIT)),
                        Map.of("entra", idpAt(null))),
                mock(ClientSessionContext.class));

        assertEquals("abc-42", token.getOtherClaims().get("project"));
    }

    @Test
    public void inheritOverImportFederationEmitsNothing() {
        AccessToken token = new AccessToken();

        // The frozen trap: the console writes IMPORT on new federations; INHERIT over
        // it freezes the cache after the first login — no claim, loudly.
        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                sessionWith(selectionParam("abc-42"), adminOnlyProfile(),
                        List.of(feederAt(IdentityProviderMapperSyncMode.INHERIT)),
                        Map.of("entra", idpAt(IdentityProviderSyncMode.IMPORT))),
                mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void feederAtImportSyncModeEmitsNothing() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                sessionWith(selectionParam("abc-42"), adminOnlyProfile(),
                        List.of(feederAt(IdentityProviderMapperSyncMode.IMPORT)),
                        Map.of("entra", idpAt(IdentityProviderSyncMode.FORCE))),
                mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void zeroFeederInstancesEmitsNothing() {
        AccessToken token = new AccessToken();

        // The matched set's missing half: without a feeder the attribute would
        // never refresh — no claim, loudly.
        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                sessionWith(selectionParam("abc-42"), adminOnlyProfile(),
                        List.of(), Map.of()),
                mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }

    @Test
    public void feederReferencingMissingIdpEmitsNothing() {
        AccessToken token = new AccessToken();

        mapper.setClaim(token, mappingModel(), userSessionWithEntitlement(ENTITLED),
                sessionWith(selectionParam("abc-42"), adminOnlyProfile(),
                        List.of(feederAt(IdentityProviderMapperSyncMode.FORCE)),
                        Map.of()), // no "entra" federation — a dangling feeder alias
                mock(ClientSessionContext.class));

        assertFalse(token.getOtherClaims().containsKey("project"));
    }
}
