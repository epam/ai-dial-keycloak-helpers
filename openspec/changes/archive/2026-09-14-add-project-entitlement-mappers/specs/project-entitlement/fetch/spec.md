# project-entitlement/fetch — delta

## ADDED Requirements

### Requirement: Brokered-login entitlement fetch

At every brokered login, the fetch mapper SHALL fetch the user's project-group
memberships from Microsoft Graph and cache the resulting entitlement on the
Keycloak user as an ordered, de-duplicated JSON array under the configured
entitlement attribute.

#### Scenario: First brokered login

- GIVEN a user authenticating through the Entra identity provider for the first time
- WHEN the brokered login completes
- THEN the entitlement attribute holds the ordered, de-duplicated list of the user's entitled project values

#### Scenario: Subsequent brokered login

- GIVEN an already-linked federated user whose identity-provider federation runs at an effective sync mode of FORCE or LEGACY
- WHEN the user logs in through the broker again
- THEN the entitlement is re-fetched and the cached attribute is replaced with the fresh result

#### Scenario: Coexistence with the existing attribute mappers

- GIVEN the existing job-title/photo attribute mappers configured alongside the fetch mapper
- WHEN a brokered login runs
- THEN each mapper caches its own attributes independently and neither affects the other

### Requirement: Delegated-token Graph access

The Graph calls SHALL run with the user's own brokered external access token
under the delegated `User.Read` permission only.

#### Scenario: Delegated access

- GIVEN a brokered login with a stored external access token
- WHEN the fetch runs
- THEN the Graph request is authorized with the user's own brokered token

#### Scenario: No application permissions

- GIVEN any configuration of the extension
- WHEN the fetch runs
- THEN no client-credentials or application-permission Graph call is ever made

### Requirement: Convention-gated value resolution

For every group in the Graph response, the entitlement value SHALL be resolved
per group: a visible display name conforming to the configured convention
yields the parsed project id (the convention prefix stripped), a visible
non-conforming name excludes the group, and a group without a visible display
name yields the group's object id.

#### Scenario: Conforming name yields the parsed project id

- GIVEN a member group whose visible display name matches the configured convention
- WHEN the entitlement is computed
- THEN the group contributes the parsed project id (the convention prefix stripped when the
  name starts with it) as its value

#### Scenario: Non-conforming name is excluded

- GIVEN a member group whose visible display name does not match the convention (e.g. a management group)
- WHEN the entitlement is computed
- THEN the group contributes nothing — in a named, unnamed, or mixed batch alike

#### Scenario: Entry with neither id nor name is skipped

- GIVEN a group entry serialized with neither an id nor a display name
- WHEN the entitlement is computed
- THEN the entry is skipped with a WARN — a malformed entry never breaks the fetch

#### Scenario: No visible name yields the object id

- GIVEN a member group serialized without a display name (Microsoft Graph's limited-information serialization under the least-privilege permission, with the display-name filter still applied server-side)
- WHEN the entitlement is computed
- THEN the group contributes its object id as its value — a real admitted membership is never dropped

#### Scenario: Mixed batch is logged loudly

- GIVEN a fetch whose response contains both named and unnamed groups
- WHEN the entitlement is computed
- THEN the anomaly is logged loudly (WARN-level, the observable signal for Graph filter drift),
  and the named/object-id counts are recorded at debug level per fetch

### Requirement: Required naming-convention configuration

The fetch mapper SHALL fail closed — clearing the cached entitlement to an
empty list and logging an ERROR that names the convention knobs — when either convention
knob (`convention.regex` or `convention.prefix`) is unconfigured or the regex
is invalid; the convention knobs carry no defaults, because the naming
convention is realm policy.

#### Scenario: Unconfigured convention

- GIVEN a fetch mapper instance without both convention knobs set
- WHEN a brokered login runs
- THEN the cached entitlement is cleared to an empty list, an ERROR log names the convention knobs, and no claim can be minted from the cleared cache

#### Scenario: Invalid convention regex

- GIVEN a `convention.regex` value that does not compile
- WHEN a fetch runs
- THEN the behavior is identical to the unconfigured case

#### Scenario: No shipped defaults

- GIVEN the extension as shipped
- WHEN no convention is configured
- THEN the jar supplies no default convention — the naming convention is always the adopting realm's policy

### Requirement: Lasting-failure handling

On a lasting fetch failure — a Graph 400, 401, or 403 response, a missing or
unparsible stored broker token, or an unconfigured or invalid convention — the
fetch SHALL clear the cached entitlement to an empty list, log an ERROR, and
write the cache timestamp.

#### Scenario: Graph rejects the call

- GIVEN a Graph response of 400, 401, or 403
- WHEN the fetch completes
- THEN the cached entitlement is cleared to an empty list and an ERROR is logged

#### Scenario: Stored broker token unusable

- GIVEN a stored broker token that is missing or unparsible
- WHEN the fetch runs
- THEN the cached entitlement is cleared to an empty list and an ERROR is logged

#### Scenario: Unverifiable membership yields no claim

- GIVEN a cleared (empty) entitlement cache
- WHEN any token mint runs
- THEN no project claim can be emitted — the failure never turns into wrong data

#### Scenario: Login still proceeds

- GIVEN a lasting fetch failure during a brokered login
- WHEN the login completes
- THEN the login is not blocked — the failure is recorded in the log and the cache only

### Requirement: Temporary-failure handling

On a temporary fetch failure — a network error, a Graph 5xx or 429 response,
or a page-cap excess — the fetch SHALL keep the previously cached entitlement
and log a WARN.

#### Scenario: Network error

- GIVEN a network-level failure reaching Graph
- WHEN the fetch runs
- THEN the previously cached entitlement is kept and a WARN is logged

#### Scenario: Graph 5xx or 429

- GIVEN a Graph response of 5xx or 429
- WHEN the fetch completes
- THEN the previously cached entitlement is kept and a WARN is logged

#### Scenario: Login still proceeds

- GIVEN a temporary fetch failure during a brokered login
- WHEN the login completes
- THEN the login is not blocked and no data is invented — the previous list stands until the next successful fetch

### Requirement: Cache timestamp

The fetch mapper SHALL write the fetch timestamp (epoch-millis, under the
fixed `projectEntitlementAt` attribute) on every successful fetch and on every
clear-to-empty.

#### Scenario: Successful fetch writes the timestamp

- GIVEN a fetch that returns a group list
- WHEN the entitlement is cached
- THEN the timestamp attribute is written alongside it

#### Scenario: Clear-to-empty writes the timestamp

- GIVEN a lasting failure that clears the entitlement to an empty list
- WHEN the clear happens
- THEN the timestamp is written — a fresh empty list is still fresh

### Requirement: Graph request timeouts

Every Graph call SHALL carry explicit connect and read timeouts, configurable
through the mapper configuration with sensible defaults.

#### Scenario: Unresponsive endpoint

- GIVEN a Graph endpoint that accepts the connection but never responds
- WHEN the read timeout elapses
- THEN the call fails as a temporary failure (previous list kept, WARN logged)

#### Scenario: Unreachable endpoint

- GIVEN a Graph endpoint that cannot be reached at all
- WHEN the connect timeout elapses
- THEN the call fails as a temporary failure

### Requirement: Bounded pagination

The fetch SHALL page Graph responses with a `$top=999` page size and a fixed
maximum page count per fetch, where a pagination chain exceeding the cap
counts as a temporary failure.

#### Scenario: Chain within the cap

- GIVEN a paginated response whose chain length is within the page cap
- WHEN the fetch completes
- THEN every page is consumed and the full membership list is returned

#### Scenario: Chain exceeding the cap

- GIVEN a pagination chain longer than the page cap
- WHEN the cap is exceeded
- THEN the fetch fails as a temporary failure (previous list kept, WARN logged) — never a silently truncated result

### Requirement: nextLink host restriction

The fetch SHALL follow an `@odata.nextLink` chain only while the link starts
with `https://graph.microsoft.com/`.

#### Scenario: nextLink on a foreign host

- GIVEN a response whose `@odata.nextLink` points at any other host
- WHEN the fetch evaluates the link
- THEN the link is refused, not followed — pagination never leaves Microsoft Graph

### Requirement: Closed error streams

The fetch SHALL always close Graph error-response streams (no leaked
connections), including on failure paths.

#### Scenario: Error response

- GIVEN a Graph error response
- WHEN it is consumed
- THEN its stream is closed before the failure is processed

### Requirement: Sync-mode declaration

The fetch mapper SHALL declare `supportsSyncMode` as {FORCE, LEGACY},
excluding IMPORT, so Keycloak's own brokered-login delegate warns at login
when a setup would freeze the cache after the first login.

#### Scenario: Cache-freezing setup warns at login

- GIVEN a fetch mapper applied where its effective setup would freeze the entitlement cache after the first login
- WHEN a brokered login runs
- THEN Keycloak's own brokered-login delegate emits its warning about the non-refreshing setup
