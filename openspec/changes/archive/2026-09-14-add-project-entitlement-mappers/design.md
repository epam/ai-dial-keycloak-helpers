# Design: add-project-entitlement-mappers

## Context

The extension already ships an established pattern: an IdP mapper hooked into
the broker login flow extracts the user's external access token, a provider
calls Microsoft Graph with it, the result is cached on the Keycloak user, and
a protocol mapper emits cached attributes as OIDC claims. This change adds
one more matched pair in that pattern — nothing existing is replaced or
modified.

## Goals and Non-Goals

**Goals:** a server-validated, singular `project` claim; zero per-project
configuration; delegated-only Graph access; fail-closed behavior on every
broken premise.

**Non-Goals:** no per-project configuration of any kind (the naming
convention is the configuration — the scale position is hundreds to thousands
of churning projects); no application permissions or client-credentials Graph
access; no role mapping (the `roles` claim vocabulary is untouched — project
transport is completely separate); no changes to the existing job-title/photo
mappers; no shared HTTP-client migration (recorded as a follow-up below).

## The matched pair

- **Fetch side** — `ProjectEntitlementIdpMapper` (IdP mapper) drives
  `MsGraphProjectGroupsProvider`: at every brokered login it extracts the
  user's external token, calls Graph for the user's group memberships, and
  caches the entitlement (plus a fetch timestamp) on the user.
- **Mint side** — `ProjectSelectionProtocolMapper` (protocol mapper) reads the
  request's selection parameter, checks every premise of the mint decision at
  the mint, and emits the claim only on a fully valid premise set.

The two are a matched set by design: the protocol mapper refuses to mint
unless the fetch side is present and healthy. A configuration error is caught
at the first mint, not discovered later as a silently stale cache.

## Key decisions

### The selection is request-carried, not session-carried

The selection (a custom token-request parameter, not a `scope` value — scope
values must be linked scope entities) travels in the token-endpoint request
itself: the exchange POST and every refresh POST carry it explicitly. Every
mint of a grant therefore carries that grant's selection, so parallel sessions
are isolated by construction and no identity-provider session state is read
or written on the mint path. A client that never sends the parameter is
unaffected — it never receives the claim.

### Fail-closed premise guards at the mint

The emit/no-emit decision stands on premises that the mapper itself verifies
before emitting; each guard fails closed — a broken premise never yields a
claim:

- **Attribute premise (detection, not prevention).** The cached entitlement
  is a plain user attribute treated as the premise of an enforcement
  decision, so its write surface is realm policy, not the jar's: the adopting
  realm declares both cache attributes admin-only in its User Profile. The
  mapper adds a detection guard over that premise — undeclared or
  user-editable yields no claim plus an ERROR log. No token mapper can defend
  a realm's attribute-write surface; the guard detects a broken premise
  (yielding no claims, loudly) instead of failing open with silent privilege.
- **Sync premise.** The entitlement refresh runs only under effective sync
  mode FORCE or LEGACY; a federation frozen on IMPORT would leave the cache
  stale after first login, silently. The guard resolves every fetch-mapper
  instance's effective mode with Keycloak's own
  `IdentityProviderMapperSyncModeDelegate.combineIdpAndMapperSyncMode` and
  refuses to mint on zero feeders or any effective IMPORT.
- **Freshness premise.** A fetch timestamp written on every successful fetch
  and every clear-to-empty feeds an optional age bound (`entitlement.max-age`
  minutes, default 0 = disabled). An enabled bound treats an older cache — or
  one without a timestamp — as absent: no claim. The bound is meant to be set
  above the realm's SSO Session Max (e.g. twice its value): a user with a
  live SSO session traverses the broker — and refreshes the cache — at least
  once per SSO session, so active users never hit the bound; only idle grants
  past their last brokered login do, and one re-login cures them.

### Per-group value resolution, no list-wide mode

The entitlement's value space is resolved per group: a convention-conforming
visible name yields the parsed project id; a non-conforming visible name is
excluded; no visible name yields the group's object id. The convention regex
gates every visible name in every case, and an admitted membership without a
visible name is never dropped. There is no list-wide mode to configure, store,
or flip; a mixed named/unnamed batch is an anomaly logged loudly with counts
— the observable signal for Graph filter drift.

### Lasting vs. temporary failures

Membership unverifiable (Graph 400/401/403, missing/unparsible stored broker
token, invalid convention) → clear the cache to empty + ERROR: no claim, and
the consumer's claim-absence detection fires downstream. Transient (network
errors, 5xx, 429, page-cap excess) → keep the previous list + WARN, with the
freshness bound capping how long a stale list can mint. Never invented data;
never a login-blocking error.

### Hardened, minimal Graph transport

Explicit configurable connect/read timeouts; `$top=999` page size; a fixed
page cap whose excess is a temporary failure (never a silently truncated
list); the `@odata.nextLink` chain followed only on `https://graph.microsoft.com/`
so pagination never leaves Graph; error streams always closed. The transport
stays on the JDK's `HttpURLConnection`, matching the existing providers'
pattern and the repo's zero-new-dependencies position.

### The convention is realm policy

`convention.regex` and `convention.prefix` carry no defaults. Only
convention-conforming groups can ever yield an entitlement value or a claim;
everything else is invisible by construction. An unconfigured convention
fails closed exactly like an invalid one — the jar never guesses a naming
convention.

## Risks and Trade-offs

- **`/me/memberOf` semantics drift**: the least-privilege permission plus
  server-side display-name filtering plus null-serialization under limited
  read is documented behavior, but a Microsoft change would surface as
  non-project groups entering the candidate list — visible non-conforming
  names are still excluded by the per-group rule, and the residual (unnamed
  groups riding in as object ids) is observable via the mixed-batch anomaly
  log.
- **Graph in the login path**: the fetch runs per brokered login; the cache
  plus the lasting/temporary split bound the blast radius, and the freshness
  bound caps staleness — never wrong claims.
- **Staleness between logins**: entitlement re-evaluation happens at brokered
  login only; revocation between logins is bounded by the freshness knob
  (disabled by default — unbounded) and by adopting-realm session lifetimes.
- **Selection is client input**: validated at mint against server-side
  entitlement; malformed values never match. The value is compared, never
  interpolated — no injection surface.

## Migration / Rollout

Deployment is a jar drop into Keycloak's `providers` directory plus restart;
the mappers are additive and inert until an administrator configures the
matched pair. The follow-up backlog records migrating the providers onto
Keycloak's own configurable HTTP client.
