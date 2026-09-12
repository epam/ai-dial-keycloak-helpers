# D-019 project-entitlement mappers — design spec

Status: **spec — authored 2026-09-09, before any `src/` change on this branch**
(`d-019-project-entitlement`, off `development`); amended 2026-09-10 (the re-mint
defect fix) and **2026-09-11 (the upstream review of the extension jar)**. The
system-level contract lives in the D-019 config repo
(`epam-keycloak-dial-config/specs/02-keycloak-broker.md`, amended 2026-09-09 through
2026-09-11) — this document specifies the extension's internals against that contract
and does not duplicate it. The 2026-09-11 pass: the protocol mapper now checks **every
premise of the mint decision at the mint** (an attribute-premise detection guard, a
sync-mode guard, an optional freshness bound with the `entitlement.max-age` knob); the
entitlement's value space is the **per-group fallback** (the list-wide named/degraded
mode dissolves); fetch failures are split **lasting/temporary** with a
`projectEntitlementAt` cache timestamp; the Graph calls are hardened (timeouts,
`$top`, a page cap, a nextLink host check); the fetch mapper declares
`supportsSyncMode` {FORCE, LEGACY}.

> **AMENDED 2026-09-10 (the re-mint defect fix — the system contract's amendment is
> normative; this banner records the deltas).** The extension is now **two pieces**, not
> three: the selection-capture authenticator (§2 below) is **RETIRED** — its
> `PROJECT_SELECTION` client-session note was frozen at the SSO user session's first
> consumer-client authorize and re-emitted cross-session (the live E2E falsification on
> 2026-09-09); per the user ruling the state is removed, not repaired — no note, no
> authenticator, no fallback. The protocol mapper (§3) now reads the selection from
> **the request itself** — the `project` form parameter of the exchange/refresh POST
> (`keycloakSession.getContext().getHttpRequest().getDecodedFormParameters()`, guarded
> for null request scope) — **no param → no claim, uniformly**; every mint of a grant
> (exchange + every refresh POST) carries the selection explicitly, so parallel
> sessions are isolated by construction. The sentences below describing the note
> capture and the "refreshes re-emit it" semantics are the falsified 2026-09-09
> design, kept as the audit trail.

> **AMENDED 2026-09-11 (the upstream review of the extension jar; the system
> contract's same-day amendment is normative, this banner records the deltas).** The
> protocol mapper (§3) now checks **every premise of the mint decision at the mint**:
> an **attribute-premise detection guard** (the entitlement attribute must be declared
> admin-only in the realm's User Profile), a **sync guard** (the realm must hold a
> healthy entitlement fetch-mapper feeder — the two mappers are a matched set), and an
> optional **freshness bound** (`entitlement.max-age`, minutes, default 0 = disabled)
> over a `projectEntitlementAt` cache timestamp. The entitlement's value space is the
> **per-group fallback** — the list-wide named/degraded "mode" dissolves into a
> per-group rule plus logging ("Graph contract"). Fetch failures are split
> **lasting/temporary** (lasting → clear to `[]`; temporary → keep previous), and the
> Graph calls are hardened: explicit connect/read timeouts, `$top=999`, a page cap
> (excess = temporary failure), a nextLink host check, error streams always closed.
> The fetch mapper declares `supportsSyncMode` = {FORCE, LEGACY} and is configured at
> syncMode FORCE in practice. The list-wide "Modes" and undifferentiated
> failure-handling bullets in "Graph contract" are rewritten in place (marked),
> matching the system contract's same-day rewording.

**Branch posture (updated 2026-09-11)**: originally local-only — a PoC for an
org-internal design, never pushed without an explicit ruling. The recorded post-PoC
option — upstreaming this as a PR to `epam/ai-dial-keycloak-helpers`, so the jar the
corporate realm already mounts gains the mapper — is now exercised: the branch is
published as a pull request to the upstream repository, and the 2026-09-11 amendments
below incorporate that pull request's review. The document is written to be read
outside its originating context.

## Purpose

Implement decision **D-019** (amended mechanics, 2026-09-09): the user's entitled
projects — Entra ID security groups whose display names follow the convention
`Project XXX-XXX` ("Project" + space + project id) — become a **server-validated,
singular `project` claim** in Keycloak-issued tokens. The session's project is a
**client selection** (custom authorize parameter), validated at every mint against the
user's **Graph-fetched entitlement**. Zero per-project configuration; zero application
permissions; the Graph calls run with the **user's brokered token** (delegated
`User.Read`).

## What this repo already provides (the pattern to follow)

| existing piece | role |
|---|---|
| `MicrosoftUserAttributesIdpMapper` (`idp/`) | IdP mapper hooked into the broker login flow |
| `MsGraphUserAttributesProvider` + `UserAttributesProvider` SPI (`provider/`) | Graph calls with the external token |
| `TokenExtractor` (`util/`) | extracts the external access token from the brokered context |
| `CachedUserAttributesProtocolMapper` (`protocol/`) | emits cached user attributes as OIDC claims |
| `MapperConfiguration` (`config/`) | mapper config properties (`fetch.job.title`, `fetch.photo`) |
| `META-INF/services` registrations | SPI wiring |
| per-class unit tests (`src/test/`) | the test conventions to mirror |

## The three new pieces

*(2026-09-10: two pieces remain — §2 below is RETIRED, see the banner.)*

### 1. `ProjectEntitlementIdpMapper` (IdP mapper, `idp/`)

Runs at every **brokered** login (first login and `update-brokered-user` — mirror the
existing mapper's hook points; the update hook runs only under effective sync mode
FORCE or LEGACY, which is what §3's sync guard stands on). Uses `TokenExtractor` to
get the user's external token, then the Graph call (§"Graph contract") and caches the
result on the Keycloak user as the attribute **`projectEntitlement`**: the ordered,
de-duplicated list of the user's entitled groups (serialized JSON array; each group's
value per the **per-group fallback** in §"Graph contract", amended 2026-09-11). Every
successful fetch — and every clear-to-`[]` — also writes a **`projectEntitlementAt`**
timestamp (epoch-millis), the freshness knob's input (§3).

**Sync-mode declaration (2026-09-11 amendment)**: the fetch mapper declares
`supportsSyncMode` = **{FORCE, LEGACY}** — IMPORT is excluded, so Keycloak's own
brokered-login delegate warns at login when a setup would freeze the cache — and is
configured at **syncMode FORCE** in practice (LEGACY also refreshes every login and is
benign; §3's sync guard carries the full resolution rule). The fetch mapper and the
protocol mapper are a **matched set**: the protocol mapper refuses to mint unless the
fetch side is present and healthy (§3's sync guard).

Replaces nothing — the existing job-title/photo fetch coexists unchanged; this is a
separate mapper class and separate user attribute.

### 2. `ProjectSelectionAuthenticator` (authenticator, new `authenticator/` package) — **[RETIRED 2026-09-10 — see the banner; do NOT re-implement from the test plan]**

The selection carrier is a **custom authorize parameter `project`** — a literal
`scope=project-X` cannot work (scope values must be linked scope entities; the amended
design has none). The authenticator is a small required-flow step that copies the
`project` parameter from the authentication session's client request parameters into a
**client session note** (`PROJECT_SELECTION`), so it survives to token-mint time.

Why a separate piece (not folded into the IdP mapper): a silent `prompt=none`
re-authorize with a live SSO cookie short-circuits the broker — the IdP mapper does not
run for those sessions. The authenticator and the protocol mapper run for **every**
session. The authenticator must be flow-position-agnostic (browser flow and silent
re-authorize both) and must not break flows that carry no `project` parameter (note
left unset → no claim → the baseline vocabulary is untouched).

### 3. `ProjectSelectionProtocolMapper` (protocol mapper, `protocol/`)

Attached (via admin config, like the existing cached-attributes mapper) to the
consumer clients' scopes. At **every token mint**:

1. Read the client session note `PROJECT_SELECTION` (the selection — untrusted input).
2. Read the user attribute `projectEntitlement` (the entitlement — server data).
3. **If selection ∈ entitlement → emit `project: "<selection>"`** as a plain JSON
   string (access token only by default; standard OIDC mapper options govern, as with
   the existing protocol mapper). **Else → emit nothing.** HTTP 200, well-formed
   token, no error surface — the silent-drop semantics the config repo's spec 03
   verifies, and the reason its CLI-side claim-verification MUST exists.

Validation is a set-membership comparison — the selection value is never interpolated,
parsed, or executed (no injection surface). Selection is fixed per client session;
refreshes re-emit it; parallel sessions carry their own.

**Amended 2026-09-11 — every premise of the mint decision is checked at the mint.**
The emit/no-emit decision above stands on premises that the mapper itself now verifies
before emitting (the selection read from the request per the 2026-09-10 banner, plus
three guards, each failing **closed** — a broken premise never yields a claim):

- **Attribute-premise guard (detection, not prevention).** The cached entitlement is a
  plain user attribute, and the mint treats it as the premise of an enforcement
  decision — so its write surface is realm policy, not the jar's. The attribute MUST
  be declared **admin-only in the realm's User Profile** (no viewer, admin-only
  editor) — the real control, and an adopting realm's checklist item. The mapper adds
  a detection guard over that premise: before emitting, it resolves the realm's
  user-profile declaration for the configured entitlement attribute — **undeclared or
  user-editable → emit NO claim + ERROR log**. A compromised premise fails closed (no
  claims, loudly) instead of failing open with silent privilege. No token mapper can
  defend a realm's attribute-write surface; this guard detects a broken premise, it
  does not prevent the write.
- **Sync guard (the two mappers are a matched set).** The entitlement refresh (§1)
  runs only under effective sync mode FORCE or LEGACY — a federation frozen on IMPORT
  would leave the cache stale after first login, silently. At every mint the mapper
  resolves the realm's entitlement fetch-mapper instances and computes each one's
  **effective** sync mode with Keycloak's own
  `IdentityProviderMapperSyncModeDelegate.combineIdpAndMapperSyncMode` (a mapper on
  INHERIT takes its federation's mode; an unset federation mode resolves to LEGACY,
  which refreshes every login and is benign; the frozen case is effective IMPORT). The
  realm must hold **at least one** fetch-mapper instance, and **every** one of them
  must have effective mode ∈ {FORCE, LEGACY}: zero feeders, or any effective IMPORT →
  **no claim + ERROR log**. A configuration error is caught at the first mint, not
  discovered later as a silently frozen cache.
- **Freshness (the `entitlement.max-age` knob — minutes, default 0 = disabled).** A
  `projectEntitlementAt` timestamp (epoch-millis) is written on every successful fetch
  and on every clear-to-`[]` ("Graph contract"). When T > 0, a cache older than T —
  or a cache with **no timestamp** — is treated as **absent → no claim** (fail closed,
  never fail open; the claim-absent path above is the failure UX). T is set **above
  the realm's SSO Session Max** (e.g. twice its value): a user with a live SSO session
  traverses the broker — and refreshes the cache — at least once per SSO session, so
  active users never hit the bound; only idle grants past their last brokered login
  do, and one re-login cures them.

All three guards resolve realm configuration at the point of use (the user-profile
declaration; the fetch-mapper instances) and are null-safe like the rest of the
mapper.

## Graph contract

```
GET https://graph.microsoft.com/v1.0/me/memberOf/microsoft.graph.group
    ?$filter=startswith(displayName,'Project%20')
    &$select=id,displayName
    &$count=true
Headers: Authorization: Bearer <external token from TokenExtractor>
         ConsistencyLevel: eventual          # required with $filter on directory objects
```

- Paginated via `@odata.nextLink` — hardened per the 2026-09-11 amendment below.
- Documented least privilege: delegated **`User.Read`**.
- **Per-group fallback (2026-09-11 amendment — replaces the list-wide named/degraded
  "modes" decision)**: the entitlement's value space is resolved **per group**. A
  visible name that conforms to the convention regex → the **parsed project id**
  (`Project EPM-AEM` → `EPM-AEM`, prefix stripped); a visible non-conforming name
  (e.g. a `Project Managers` group) → **excluded**; no visible name
  (`displayName: null` — no consent; Graph's documented "limited information"
  serialization, **the filter still applied server-side**, verified 2026-09-09) → the
  group's **object ID**. The regex gates every visible name in every case; a
  null-name group is a real membership the Graph filter admitted and is **never
  dropped**. A genuinely mixed batch (some names visible, some null) is an anomaly —
  **logged loudly** (org reality: groups are all-named or all-null; the log is the
  observable signal for Graph-filter drift), with named/GUID counts logged per fetch.
  There is no list-wide mode to configure, store, or flip.
- **Failure handling (amended 2026-09-11 — the lasting/temporary split)**:
  **lasting** failures — Graph 400/401/403, a missing or unparsible stored broker
  token, an invalid convention regex — **clear the cached list to `[]` + ERROR log**
  (membership unverifiable → no claim → the client's mandatory detection fires
  downstream). **Temporary** failures — network errors, Graph 5xx, 429, a page-cap
  excess (below) — **keep the previous list + WARN log**. Never invented data; never
  a login-blocking error.
- **Cache timestamp (2026-09-11 amendment)**: `projectEntitlementAt` (epoch-millis) is
  written on **every successful fetch and on every clear-to-`[]`** — a fresh empty
  list is still fresh. It is the freshness knob's input (§3).
- **Graph-call hardening (2026-09-11 amendment)**: explicit **connect and read
  timeouts** (configurable via mapper config, with sensible defaults); **`$top=999`**
  page size; a **page cap** (a fixed maximum page count per fetch, e.g. 20 pages) — a
  pagination chain exceeding the cap counts as a **temporary** failure; the
  `@odata.nextLink` chain is followed **only** while the link starts with
  `https://graph.microsoft.com/` — a nextLink on any other host is refused, so
  pagination never leaves Graph; error response streams are **always closed**
  (try-with-resources / finally — no leaked connections). The implementation stays on
  the JDK's `HttpURLConnection`, matching the existing providers' pattern; a shared
  HTTP-client migration is recorded as a follow-up, not built here.

## Configuration knobs (mapper config, `MapperConfiguration` pattern)

| key | label | default |
|---|---|---|
| `convention.regex` | Project group name regex | `^Project [A-Za-z0-9]+-[A-Za-z0-9]+$` |
| `convention.prefix` | Project id prefix strip | `Project ` |
| `selection.param` | Selection parameter name (authorize URL + token-endpoint form parameter) | `project` |
| `claim.name` | Emitted claim name | `project` |
| `entitlement.attribute` | Cached user attribute key | `projectEntitlement` |
| `entitlement.max-age` | Entitlement cache freshness bound, minutes — protocol mapper; 0 = disabled (2026-09-11) | `0` |

The convention regex is the D-019 "fail loud on unrecognized names" mechanism:
**only convention-conforming groups can ever yield an entitlement value or a claim**;
anything else is invisible by construction (recorded at debug level, never emitted).
Knob ownership (2026-09-11 note): the convention keys belong to the fetch mapper, the
selection/claim keys to the protocol mapper, `entitlement.attribute` is shared
(written by the fetch mapper, read by the protocol mapper), and
`entitlement.max-age` is the protocol mapper's freshness bound (§3).

## Non-goals

- No per-project configuration of any kind (scale ruling — hundreds to thousands of
  churning projects).
- No application permissions / client-credentials Graph access (the user's delegated
  token only).
- No roles: the entitlement bridge touches no role mapping — the config repo's HARD
  RULE (`roles` claim = dial roles only) is structural here.
- No changes to the existing job-title/photo mappers.

## Test plan

- **Unit** (mirror the existing per-class conventions): regex/prefix parsing;
  **per-group fallback cases** (all-named batch → parsed ids, regex-gated; all-null
  batch → object IDs; mixed batch → parsed ids where visible + object IDs where not,
  with the anomaly logged and named/GUID counts logged per fetch; a non-conforming
  visible name excluded in **every** case — named, null, or mixed batch); entitlement
  serialization/de-dup; selection validation (member/non-member/malformed/absent
  param); ~~authenticator param capture (present/absent/multiple values — first wins,
  logged)~~ **[RETIRED 2026-09-10]**; protocol-mapper emission matrix (now:
  **request-param-carried** selection × entitlement × token type, incl. null request
  context / null session — run with the premise guards satisfied: the fixture realm
  declares the attribute admin-only and holds a valid FORCE feeder, so the guards
  multiply into every emit case without drowning the matrix).
- **Premise guards (2026-09-11 amendment)** — attribute premise: attribute declared
  admin-only → emits; undeclared → no claim + ERROR; user-editable → no claim +
  ERROR. Sync: feeder at FORCE → emits; LEGACY → emits; INHERIT over a FORCE
  federation → emits; INHERIT over an unset federation (resolves LEGACY) → emits;
  INHERIT over an explicitly-IMPORT federation → no claim + ERROR; feeder at IMPORT →
  no claim + ERROR; zero feeder instances → no claim + ERROR. Freshness: cache newer
  than T → emits; cache older than T → no claim; **T = 0 → no age check** (emits
  regardless of timestamp age or absence); **missing timestamp with T > 0 → no
  claim**.
- **Failure classes (2026-09-11 amendment)** — per class: lasting (Graph 400; 401;
  403; missing stored broker token; unparsible stored broker token; invalid
  convention regex) → cleared to `[]` + ERROR; temporary (network error; Graph 5xx;
  429) → previous list kept + WARN. Timestamp: written on every successful fetch, and
  on every clear-to-`[]`.
- **Graph-call hardening (2026-09-11 amendment)** — against a **local test server**
  (no external calls): a no-response endpoint → the connect/read timeout fires; a
  pagination chain longer than the page cap → temporary failure (previous list kept +
  WARN); a `@odata.nextLink` pointing at a foreign host → refused, not followed;
  error responses → streams closed (no leaked connections).
- **Integration = the rig** (not in-repo): the config repo's `specs/03-verification.md`
  is the end-to-end test (one real corporate browser login + silent round-2); its
  checks P2-V1..P2-V8 are this extension's acceptance criteria. Nothing here duplicates
  them.

## Build & deployment

- JDK 17+, Gradle 8+ — `./gradlew clean build check` (checkstyle runs as part of
  `check`); artifact `build/libs/aidial-keycloak-mappers-<version>.jar`.
- Deployment: jar into Keycloak's `providers` directory + restart. Rig: a compose
  volume mount (wired by the implementation round; recorded in the config repo's
  manifest).
- Supply-chain: this repo's `trivy.yaml` / `.trivyignore` conventions apply to the
  dependency set the new code pulls in (prefer zero new dependencies — plain JDK HTTP
  plumbing (`HttpURLConnection`, per the hardening bullet above) + hand-rolled JSON
  handling, matching the existing code's minimalism).
