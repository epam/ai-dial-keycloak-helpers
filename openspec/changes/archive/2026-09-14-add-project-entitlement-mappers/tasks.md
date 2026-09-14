# Tasks: add-project-entitlement-mappers

## 1. Configuration

- [x] `ProjectEntitlementConfiguration`: the shared knob set — `convention.regex`
      and `convention.prefix` (required, no defaults), `selection.param` and
      `claim.name` (default `project`), `entitlement.attribute` (shared cache
      key), `entitlement.max-age` (minutes, default 0 = disabled), plus the
      Graph timeout knobs (§3)

## 2. Model

- [x] `ProjectEntitlement`: ordered, de-duplicated entitlement serialization;
      per-group value resolution (conforming name → parsed project id,
      non-conforming name → excluded, no visible name → object id) with the
      mixed-batch anomaly logging and per-fetch named/object-id counts

## 3. Graph provider

- [x] `MsGraphProjectGroupsProvider`: the `/me/memberOf` call with the
      display-name filter, `$select`, `$count=true`, `ConsistencyLevel:
      eventual`, delegated `User.Read` only
- [x] Hardening: configurable connect/read timeouts, `$top=999`, the page cap
      (excess = temporary failure), the `@odata.nextLink` host restriction,
      always-closed error streams
- [x] `GraphFetchException`: the lasting/temporary failure classification

## 4. Fetch mapper

- [x] `ProjectEntitlementIdpMapper`: brokered-login fetch via the stored
      external token, entitlement + timestamp caching, coexistence with the
      existing attribute mappers
- [x] `supportsSyncMode` declared as {FORCE, LEGACY} (IMPORT excluded —
      Keycloak's own delegate warns on a cache-freezing setup)

## 5. Protocol mapper

- [x] `ProjectSelectionProtocolMapper`: request-carried selection (exchange +
      every refresh POST), null-request-scope guard, no IdP session state
- [x] Set-membership validation and emission (plain JSON string, silent drop,
      access token by default)
- [x] Attribute-premise guard (both cache attributes resolved against the
      User Profile; undeclared or user-editable → no claim + ERROR)
- [x] Sync guard (effective-mode resolution over all fetch-mapper instances;
      zero feeders or any effective IMPORT → no claim + ERROR)
- [x] Freshness bound (`entitlement.max-age`; older-than-bound or
      timestamp-less cache → no claim)

## 6. Tests

- [x] Per-class unit tests mirroring the repo's existing conventions
- [x] Per-group fallback cases: all-named, all-unnamed, mixed (anomaly
      logged), non-conforming name excluded in every batch composition
- [x] Emission matrix: request-carried selection × entitlement × token type,
      incl. null request context / null session, run with the premise guards
      satisfied
- [x] Premise guards: attribute premise (admin-only / undeclared /
      user-editable), sync (FORCE / LEGACY / INHERIT-over-FORCE /
      INHERIT-over-unset / INHERIT-over-IMPORT / IMPORT / zero feeders),
      freshness (newer / older / T=0 / missing timestamp)
- [x] Failure classes: lasting (400 / 401 / 403 / missing token / unparsible
      token / invalid convention) → cleared + ERROR; temporary (network /
      5xx / 429) → kept + WARN; timestamp written on success and on clear
- [x] Hardening, against a stub Graph endpoint (no external calls): timeout
      firing, page-cap excess, foreign-host nextLink refused, streams closed

## 7. Documentation

- [x] README: the matched-pair setup section with the four fail-closed
      requirements and the consumer-side local-verification guidance
- [x] This change's spec deltas (the two capability specs)
