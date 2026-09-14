# project-entitlement/selection-claim Specification

## Purpose
Emits the singular `project` claim at every token mint: the protocol mapper
reads the request-carried selection, verifies every premise of the mint
decision (attribute premise, sync mode, freshness) at the mint, and emits
the claim only when the selection is a member of the cached entitlement —
silently emitting nothing otherwise.

## Requirements

### Requirement: Request-carried selection

At every token mint, the protocol mapper SHALL read the session's project
selection exclusively from the configured selection parameter of the
token-endpoint request (the form parameter of the exchange POST and of every
refresh POST), reading and writing no identity-provider session state.

#### Scenario: Selection on the exchange

- GIVEN a token exchange POST whose form parameters carry the selection parameter with a value
- WHEN the token is minted
- THEN the mint sees that selection

#### Scenario: Selection on every refresh

- GIVEN a refresh POST carrying the selection parameter
- WHEN the access token is re-minted
- THEN the fresh selection is used — every mint of a grant carries the selection explicitly, so parallel sessions are isolated by construction

#### Scenario: No parameter, no claim

- GIVEN a token request without the selection parameter
- WHEN the token is minted
- THEN no project claim is emitted, uniformly, regardless of the cached entitlement

#### Scenario: Mint outside request scope

- GIVEN a mint whose request context or HTTP request is null (outside request scope)
- WHEN the mapper runs
- THEN it emits nothing and does not fail

### Requirement: Set-membership validation and emission

The protocol mapper SHALL emit the configured claim carrying the selection
value — as a plain JSON string — when the selection is a member of the cached
entitlement, and emit nothing otherwise (HTTP 200, well-formed token, silent
drop).

#### Scenario: Entitled selection emits the claim

- GIVEN a request whose selection is a member of the cached entitlement
- WHEN the token is minted
- THEN the token carries the claim as a plain JSON string equal to the selection value

#### Scenario: Unentitled selection is silently dropped

- GIVEN a request whose selection is not a member of the cached entitlement
- WHEN the token is minted
- THEN the response is HTTP 200 with a well-formed token carrying no project claim — the drop is silent by design, so consumers must detect the claim's absence

#### Scenario: Malformed selection never matches

- GIVEN a selection value that is malformed or empty
- WHEN it is validated
- THEN it simply never matches the entitlement (silent drop) — the value is compared for membership, never interpolated, parsed, or executed, so there is no injection surface

#### Scenario: Malformed cached entitlement fails closed

- GIVEN a cached entitlement attribute whose stored JSON does not parse
- WHEN a mint runs
- THEN no claim is emitted and the malformation is logged

#### Scenario: The claim lands in the access token only

- GIVEN any protocol-mapper configuration, including one that enables ID-token or UserInfo inclusion
- WHEN the claim is emitted
- THEN it lands in the access token only — the claim transport stays out of the ID token and UserInfo by design

### Requirement: Attribute-premise guard

Before emitting, the protocol mapper SHALL resolve the realm's User Profile
declarations for both cache attributes (the entitlement attribute and its
fetch-timestamp attribute) and emit no claim — logging an ERROR — when either
is undeclared or user-editable.

#### Scenario: Admin-only declarations satisfy the guard

- GIVEN both cache attributes declared admin-only in the realm's User Profile
- WHEN a mint runs with the other premises satisfied
- THEN the guard passes and the emit decision proceeds

#### Scenario: Undeclared attribute blocks the claim

- GIVEN either cache attribute not declared in the realm's User Profile
- WHEN a mint runs
- THEN no claim is emitted and an ERROR is logged

#### Scenario: User-editable attribute blocks the claim

- GIVEN either cache attribute declared with a user-editable write surface
- WHEN a mint runs
- THEN no claim is emitted and an ERROR is logged — a user-writable entitlement would let any account self-grant projects, and a user-writable timestamp would defeat the freshness bound

#### Scenario: Malformed declaration blocks the claim

- GIVEN either cache attribute whose User Profile declaration is malformed (no permissions or no edit key)
- WHEN a mint runs
- THEN the declaration does not satisfy the guard — no claim is emitted

#### Scenario: A compromised premise fails closed

- GIVEN an entitlement attribute whose write surface is not admin-only
- WHEN a mint runs
- THEN the mapper yields no claims, loudly — never silent privilege

### Requirement: Sync guard

At every mint, the protocol mapper SHALL resolve the realm's entitlement
fetch-mapper instances, compute each one's effective sync mode (a mapper on
INHERIT takes its federation's mode; an unset federation mode resolves to
LEGACY), and emit no claim — logging an ERROR — unless the realm holds at
least one fetch-mapper instance and every one of them is effectively FORCE or
LEGACY.

#### Scenario: Feeder at FORCE emits

- GIVEN a realm holding one fetch-mapper instance at sync mode FORCE
- WHEN a mint runs with the other premises satisfied
- THEN the guard passes and the emit decision proceeds

#### Scenario: Feeder at LEGACY emits

- GIVEN a fetch-mapper instance at sync mode LEGACY
- WHEN a mint runs
- THEN the guard passes

#### Scenario: INHERIT over a FORCE federation emits

- GIVEN a fetch-mapper instance at INHERIT on a federation configured at FORCE
- WHEN a mint runs
- THEN the effective mode is FORCE and the guard passes

#### Scenario: INHERIT over an unset federation emits

- GIVEN a fetch-mapper instance at INHERIT on a federation with no mode set
- WHEN a mint runs
- THEN the effective mode resolves to LEGACY (refreshes every login) and the guard passes

#### Scenario: INHERIT over an explicit IMPORT federation blocks the claim

- GIVEN a fetch-mapper instance at INHERIT on a federation explicitly configured at IMPORT
- WHEN a mint runs
- THEN no claim is emitted and an ERROR is logged

#### Scenario: Feeder at IMPORT blocks the claim

- GIVEN a fetch-mapper instance at sync mode IMPORT
- WHEN a mint runs
- THEN no claim is emitted and an ERROR is logged — the cache would freeze after the first login

#### Scenario: Zero feeder instances blocks the claim

- GIVEN a realm holding no entitlement fetch-mapper instance
- WHEN a mint runs
- THEN no claim is emitted and an ERROR is logged

### Requirement: Freshness bound

The protocol mapper SHALL support an `entitlement.max-age` bound in minutes
(default 0, disabled): when the bound is enabled, a cache older than the
bound — or one without a fetch timestamp — yields no claim.

#### Scenario: Cache newer than the bound emits

- GIVEN the bound enabled at T minutes and a cache written less than T minutes ago
- WHEN a mint runs with the other premises satisfied
- THEN the emit decision proceeds

#### Scenario: Cache older than the bound yields no claim

- GIVEN the bound enabled at T minutes and a cache written more than T minutes ago
- WHEN a mint runs
- THEN no claim is emitted

#### Scenario: Missing timestamp with the bound enabled yields no claim

- GIVEN the bound enabled and a cache without a fetch timestamp
- WHEN a mint runs
- THEN the cache is treated as absent — no claim is emitted

#### Scenario: Unparsable timestamp with the bound enabled yields no claim

- GIVEN the bound enabled and a cache whose timestamp attribute is present but unparsible
- WHEN a mint runs
- THEN the cache is treated as absent — no claim is emitted

#### Scenario: Disabled bound performs no age check

- GIVEN the bound at its default 0 (disabled)
- WHEN a mint runs
- THEN no age check applies — the cache mints regardless of timestamp age or absence

### Requirement: Roles-claim separation

The entitlement bridge SHALL carry no role mapping — no project value may
appear in any role claim, and the user's roles claim is identical whether or
not the project claim is present.

#### Scenario: Roles unchanged by the project claim

- GIVEN two tokens for the same user under the same conditions, one carrying the project claim and one without it
- WHEN their roles claims are compared
- THEN the roles claims are identical — no project value appears in any role claim

### Requirement: Mapper configuration knobs

The mappers SHALL expose their configuration knobs with the documented
defaults: `selection.param` and `claim.name` default to `project`,
`entitlement.attribute` names the shared cache key (written by the fetch
mapper, read by the protocol mapper), `entitlement.max-age` defaults to 0,
`graph.connect.timeout` and `graph.read.timeout` default to sensible values,
and the convention knobs are required with no defaults.

#### Scenario: Defaults of a fresh configuration

- GIVEN freshly added mapper instances with no customization
- WHEN their configuration is inspected
- THEN `selection.param` and `claim.name` read `project`, `entitlement.max-age` reads 0, and the convention knobs are unset — required

#### Scenario: Knob ownership

- GIVEN the two mappers configured as a matched pair
- WHEN their configurations are inspected
- THEN both mappers expose the full shared property list, while each key's help text names its effective owner: the convention keys and the Graph timeouts drive the fetch mapper, the selection, claim, and freshness keys drive the protocol mapper, and the entitlement attribute key is shared (written by the fetch mapper, read by the protocol mapper)
