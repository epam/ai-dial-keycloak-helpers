## Context

The protocol mapper currently reads the request's `project` form parameter at
every mint and emits the singular `project` claim only when the selection is a
member of the cached entitlement; an absent or empty parameter is a uniform
silent drop. The cached entitlement is a JSON array of project ids on the user
(`entitlement.attribute`), governed by the attribute-premise, sync, and
freshness guards, and the mapper is access-token-only with a RuntimeException
backstop. See proposal.md for why a list transport is needed now.

## Goals / Non-Goals

**Goals:**

- A single, well-defined trigger for the list claim: the selection parameter
  present but empty on the mint's own POST.
- Guard parity: the list emission sits behind the same attribute-premise, sync,
  and freshness premises as the singular emission — one set of guards, shared.
- The three contract points a consuming client will verify against, stated
  identically everywhere they appear: the claim-name default (`user-projects`),
  the one-trigger rule (the list claim only on the present-but-empty mint),
  and the guard inheritance (the same premises govern both claim shapes).

**Non-Goals:**

- Always-on emission of the list claim (baseline tokens stay minimal: the
  claim is a replaceable transport, nothing extra on tokens otherwise).
- Any change to the singular `project` claim behavior, the fetch mapper, the
  Graph providers, or the guards' own logic.
- Server-side enforcement changes — the claim remains informational for
  consumers.

## Decisions

**1. Empty parameter routes to the list path; guards run once, before the
routing.** `doSetClaim` already resolves config, realm, attribute premise, and
sync premise before touching the selection. The routing change is minimal:
absent parameter → return (unchanged); the entitlement read, freshness bound,
and JSON parse happen as today; the empty/non-empty branch is taken only at
emission, on fully parsed entitlement data. Alternative — a separate list
mapper instance — rejected: two mappers would resolve the guards twice and
invite drift between them.

**2. Unusable entitlement data on the list mint → no claim, never an empty
array.** An emitted `[]` is an
affirmative assertion of *verified* emptiness; when the cache is absent, stale,
or malformed, emptiness was never verified, and minting `[]` would put a false
verified-negative on the token — foreclosing the consumer's designed
"IdP does not provide the list" manual-degradation path. The spec's existing
fail-closed rulings already treat an unusable cache as absent (no claim); the
list path inherits that semantics unchanged. Alternative — emit `[]` to give
the consumer a definite answer — rejected: a definite wrong answer is worse
than an absent one, and the consumer side accepts both encodings.

**3. The claim value is the parsed `List<String>`, not a JSON string.** Putting
the parsed list into the token's other-claims lets Keycloak's serialization
produce a real JSON array; encoding the JSON text as a string would
double-encode it and break the consumer's array expectation. The list is the
parsed cache value itself (Jackson preserves array order), so the token's
array equals the cached entitlement element-for-element.

**4. New knob `list.claim.name`, default `user-projects`, added to the shared
property list.** The configuration class already shares one property list
between both mappers with per-key ownership named in the help text; the new key
follows that pattern (protocol-mapper-owned, like `claim.name`). Alternative —
reusing `claim.name` with a list mode flag — rejected: two independent claim
names read clearer in the admin console and keep the singular contract's
configuration untouched.

**5. The empty-param mint carries no singular claim.** The branch is exclusive:
empty parameter emits the list claim and returns; no code path can emit both
shapes on one mint. This keeps the singular contract's "selection ⇒ claim"
equivalence exact for consumers that verify the claim's presence.

## Risks / Trade-offs

- [Client-triggered disclosure] The empty-parameter trigger is client input: any
  client the protocol mapper is exposed to can request the full entitlement list
  by sending a blank selection parameter — that is the design (the picker asks
  for the list), and the data disclosed is the user's own entitlement to a
  client acting for that user. The admin's lever is mapper exposure: only
  clients carrying the protocol mapper (directly or via client-scope
  assignment) can trigger either claim shape, so a realm gates third-party
  clients by not granting them the entitlement scope. A pre-existing client
  that incidentally sends a blank form field flips from no claims to full
  disclosure + larger tokens — a consumer-side template bug worth avoiding,
  but the disclosed data stays within the user-client relationship the
  authorization already established.
- [Token size] The list mint carries every entitled project, not one selection
  → the entitlement list is already bounded by the user's Graph group page cap;
  the empty-param mint is the client's explicit request for the list, so the
  size is opt-in per mint.
- [Unvalidated claim-name knobs] A blank or reserved token-field claim name
  (e.g. `aud`) misconfigures the emission — the same footgun the existing
  `claim.name` knob has; the house style for configuration is lenient parsing
  (never fail the mapper on config values), and the misconfiguration is owned
  by the same admin who declares the knob. Accepted for the list claim as the
  symmetric case, not a new class to solve in isolation.
- [Upgrade ambiguity for consumers] Older consumers see the list claim as an
  unknown claim → other-claims are additive; unknown claims are inert to a
  consumer that does not read them, and singular-claim behavior is untouched.
- [Both knobs the same name] A realm setting `list.claim.name` equal to `claim.name`
  → safe by construction: the branches are exclusive and each mint performs at most
  one claim put, so a string and a list value never collide on one mint; asserted
  by test.
- [Double emission by misconfiguration] A realm configuring two protocol-mapper
  instances would emit the list claim twice with the same name → last-write-wins
  in the other-claims map with identical values; harmless, and the same holds
  today for the singular claim.

## Migration Plan

No data or config migration: the new knob defaults to `user-projects`, and
realms that never send an empty selection parameter observe zero behavior
change. Rollback is a revert — no attribute, cache, or realm-policy state is
added or reinterpreted.
