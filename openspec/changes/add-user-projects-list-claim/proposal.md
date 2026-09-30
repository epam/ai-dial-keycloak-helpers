## Why

A consumer that renders the user's project choice as a picker needs the full
entitled project list, but the only claim today is the singular `project`
string minted from the request's selection parameter — a client that has not
made a selection yet has no way to learn which projects it may offer. The
entitlement data is already cached on the user; the missing piece is a token
transport for the whole list.

## What Changes

- When the `project` selection parameter is **present but empty** on the
  token POST, the protocol mapper now emits the **full cached entitlement**
  as a list-valued claim, default name `user-projects`, as a JSON array of
  strings (new `list.claim.name` config key).
- No always-on emission: the list claim exists **only** on the
  present-but-empty mint. A request without the parameter mints no list
  claim (baseline tokens stay minimal — the claim is a replaceable
  transport, nothing extra toward the platform otherwise).
- The singular `project` claim behavior is untouched: a present, non-empty,
  entitled selection emits the singular claim exactly as before. The
  present-but-empty mint carries **no** singular claim.
- The list claim inherits every existing premise guard — the
  attribute-premise guard, the sync guard, the freshness bound — and the
  RuntimeException backstop, and stays access-token-only.
- **Fail-closed semantics on unusable data**: when the cached entitlement is
  absent, stale, or malformed, the present-but-empty mint emits **no** claim
  (never an empty array) — an empty array is an affirmative assertion of
  verified emptiness, and it is emitted only when the entitlement data is
  usable and genuinely empty. Absent/malformed data keeps the consumer's
  graceful "IdP does not provide the list" degradation instead of a false
  verified-negative.
- Absent-parameter and unentitled-selection mints remain silent drops,
  unchanged.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `project-entitlement/selection-claim`: adds the present-but-empty trigger
  and the list-valued `user-projects` emission alongside the singular-claim
  requirements; restates the no-parameter rule to distinguish the absent
  param (no claims) from the empty param (list claim only); extends the
  mapper-configuration-knobs requirement with the `list.claim.name` key.

## Impact

- `ProjectSelectionProtocolMapper` — new emission branch on the
  present-but-empty selection param, sharing the guards with the singular
  path.
- `ProjectEntitlementConfiguration` — new `list.claim.name` knob (default
  `user-projects`) surfaced in both mappers' shared property list.
- `openspec/specs/project-entitlement/selection-claim/spec.md` — delta via
  this change.
- `ProjectSelectionProtocolMapperTest` — new cases for the list-claim shape,
  per-guard suppression, ID-token exclusion, and the absent/unentitled
  regressions.
- README — the entitlement-pair section documents the new trigger and claim.
- Consumer compatibility (informational): a consumer treats an absent or
  malformed list claim as "the IdP does not provide the list" (manual-type
  degradation) and an empty array as a valid empty list; both encodings are
  already compatible.
