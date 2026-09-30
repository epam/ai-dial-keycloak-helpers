# project-entitlement/selection-claim — Delta

## MODIFIED Requirements

### Requirement: Request-carried selection

At every token mint, the protocol mapper SHALL read the session's project
selection exclusively from the configured selection parameter of the
token-endpoint request (the form parameter of the exchange POST and of every
refresh POST), reading and writing no identity-provider session state. The
parameter's presence distinguishes the two mint shapes: an absent parameter
is a no-selection mint, and a present-but-empty parameter is a list mint.

#### Scenario: Selection on the exchange

- GIVEN a token exchange POST whose form parameters carry the selection parameter with a value
- WHEN the token is minted
- THEN the mint sees that selection

#### Scenario: Selection on every refresh

- GIVEN a refresh POST carrying the selection parameter
- WHEN the access token is re-minted
- THEN the fresh selection is used — every mint of a grant carries the selection explicitly, so parallel sessions are isolated by construction

#### Scenario: No parameter, no claim

- GIVEN a token request without the selection parameter (the parameter absent entirely)
- WHEN the token is minted
- THEN no claim is emitted, uniformly, regardless of the cached entitlement — neither the singular claim nor the list claim

#### Scenario: Mint outside request scope

- GIVEN a mint whose request context or HTTP request is null (outside request scope)
- WHEN the mapper runs
- THEN it emits nothing and does not fail

### Requirement: Set-membership validation and emission

The protocol mapper SHALL emit the configured claim carrying the selection
value — as a plain JSON string — when the selection is a non-empty member of
the cached entitlement, and emit nothing otherwise (HTTP 200, well-formed
token, silent drop). A present-but-empty selection is not a membership case:
it routes to the empty-selection list emission.

#### Scenario: Entitled selection emits the claim

- GIVEN a request whose selection is a member of the cached entitlement
- WHEN the token is minted
- THEN the token carries the claim as a plain JSON string equal to the selection value

#### Scenario: Unentitled selection is silently dropped

- GIVEN a request whose selection is not a member of the cached entitlement
- WHEN the token is minted
- THEN the response is HTTP 200 with a well-formed token carrying no project claim — the drop is silent by design, so consumers must detect the claim's absence

#### Scenario: Malformed selection never matches

- GIVEN a selection value that is malformed
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

### Requirement: Mapper configuration knobs

The mappers SHALL expose their configuration knobs with the documented
defaults: `selection.param` and `claim.name` default to `project`,
`list.claim.name` defaults to `user-projects`,
`entitlement.attribute` names the shared cache key (written by the fetch
mapper, read by the protocol mapper), `entitlement.max-age` defaults to 0,
`graph.connect.timeout` and `graph.read.timeout` default to sensible values,
and the convention knobs are required with no defaults.

#### Scenario: Defaults of a fresh configuration

- GIVEN freshly added mapper instances with no customization
- WHEN their configuration is inspected
- THEN `selection.param` and `claim.name` read `project`, `list.claim.name` reads `user-projects`, `entitlement.max-age` reads 0, and the convention knobs are unset — required

#### Scenario: Knob ownership

- GIVEN the two mappers configured as a matched pair
- WHEN their configurations are inspected
- THEN both mappers expose the full shared property list, while each key's help text names its effective owner: the convention keys and the Graph timeouts drive the fetch mapper, the selection, claim, list-claim, and freshness keys drive the protocol mapper, and the entitlement attribute key is shared (written by the fetch mapper, read by the protocol mapper)

## ADDED Requirements

### Requirement: Empty-selection list emission

When the selection parameter is present but empty on the token request, the
protocol mapper SHALL emit the configured list claim — a JSON array of
strings carrying the user's full cached entitlement — and no singular claim.

#### Scenario: Empty parameter emits the full entitlement list

- GIVEN a token request whose selection parameter is present but empty, and a usable cached entitlement
- WHEN the token is minted
- THEN the token carries the list claim as a JSON array of strings equal to the cached entitlement, and carries no singular claim

#### Scenario: Empty parameter with a genuinely empty entitlement emits an empty array

- GIVEN a present-but-empty selection parameter and a cached entitlement that parses to an empty list
- WHEN the token is minted
- THEN the list claim is an empty JSON array — a verified negative

#### Scenario: The list claim lands in the access token only

- GIVEN a present-but-empty selection parameter and any protocol-mapper configuration, including one that enables ID-token or UserInfo inclusion
- WHEN the list claim is emitted
- THEN it lands in the access token only

### Requirement: No always-on list emission

The protocol mapper SHALL emit no list claim on any mint whose selection
parameter is absent or non-empty — the list claim exists only on the
present-but-empty mint, and baseline tokens stay minimal.

#### Scenario: No always-on emission of the list claim

- GIVEN a token request whose selection parameter is absent or non-empty, under any entitlement state
- WHEN the token is minted
- THEN no list claim is emitted

### Requirement: Guard parity of the list emission

The protocol mapper SHALL apply every premise guard of the mint decision —
the attribute-premise guard, the sync guard, and the freshness bound — to
the list emission exactly as it applies them to the singular emission: when
the cached entitlement is absent, stale, or malformed, the list mint emits
no claim at all. An empty array is an affirmative assertion of verified
emptiness and is never fabricated for entitlement data the mapper could not
verify.

#### Scenario: Empty parameter with no cached entitlement emits nothing

- GIVEN a present-but-empty selection parameter and no cached entitlement attribute
- WHEN the token is minted
- THEN no claim is emitted — no list claim and no fabricated empty array, so the consumer's no-list degradation applies instead of a false verified negative

#### Scenario: Empty parameter with a stale cache emits nothing

- GIVEN a present-but-empty selection parameter and a cached entitlement older than the enabled freshness bound (or without a usable fetch timestamp)
- WHEN the token is minted
- THEN the cache is treated as absent — no claim is emitted

#### Scenario: Empty parameter with a malformed cache emits nothing

- GIVEN a present-but-empty selection parameter and a cached entitlement whose stored JSON does not parse
- WHEN the token is minted
- THEN no claim is emitted and the malformation is logged

#### Scenario: Guards govern the list emission

- GIVEN a present-but-empty selection parameter while any premise guard fails (an undeclared or user-editable cache attribute, an effectively IMPORT or missing fetch-mapper feeder, or no realm scope)
- WHEN the token is minted
- THEN no claim is emitted and the guard logs as it does for the singular emission
