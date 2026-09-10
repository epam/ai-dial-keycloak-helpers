# D-019 project-entitlement mappers — design spec

Status: **spec — authored 2026-09-09, before any `src/` change on this branch**
(`d-019-project-entitlement`, off `development`). The system-level contract lives in
the D-019 config repo (`epam-keycloak-dial-config/specs/02-keycloak-broker.md`,
amendment of 2026-09-09) — this document specifies the extension's internals against
that contract and does not duplicate it.

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

**Branch posture**: local only. Never pushed without an explicit ruling — this branch
carries a PoC for an org-internal design. **Post-PoC option (recorded, not decided)**:
upstreaming this as a PR to `epam/ai-dial-keycloak-helpers` is the ideal corporate-ask
story — the jar the corporate realm already mounts gains the mapper upstream. That
conversation happens only after the PoC verifies (config-repo spec 03).

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

### 1. `ProjectEntitlementIdpMapper` (IdP mapper, `idp/`)

Runs at every **brokered** login (first login and `update-brokered-user` — mirror the
existing mapper's hook points). Uses `TokenExtractor` to get the user's external token,
then the Graph call (§"Graph contract") and caches the result on the Keycloak user as
the attribute **`projectEntitlement`**: the ordered, de-duplicated list of the user's
convention-matching projects (serialized JSON array; values per §"Modes").

Replaces nothing — the existing job-title/photo fetch coexists unchanged; this is a
separate mapper class and separate user attribute.

### 2. `ProjectSelectionAuthenticator` (authenticator, new `authenticator/` package)

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

## Graph contract

```
GET https://graph.microsoft.com/v1.0/me/memberOf/microsoft.graph.group
    ?$filter=startswith(displayName,'Project%20')
    &$select=id,displayName
    &$count=true
Headers: Authorization: Bearer <external token from TokenExtractor>
         ConsistencyLevel: eventual          # required with $filter on directory objects
```

- Paginated via `@odata.nextLink`.
- Documented least privilege: delegated **`User.Read`**.
- **Modes**: `displayName` populated (tenant granted delegated `GroupMember.Read.All`)
  → entitlement values are **parsed project ids** (`Project EPM-AEM` → `EPM-AEM`,
  prefix stripped); `displayName: null` (no consent — Graph's documented
  "limited information" serialization; **the filter is still applied server-side**,
  verified 2026-09-09) → entitlement values are the groups' **object IDs** (degraded
  mode). The mode is decided per batch by the presence of non-null `displayName`; a
  mixed batch is treated as degraded (fail conservative) and logged.
- **Failure handling** (explicit): Graph unreachable / 4xx / 5xx at login → keep the
  user's previous `projectEntitlement` if one exists, else set it empty → no claim →
  the client's mandatory detection fires downstream. **Never invented data; never a
  login-blocking error.**

## Configuration knobs (mapper config, `MapperConfiguration` pattern)

| key | label | default |
|---|---|---|
| `convention.regex` | Project group name regex | `^Project [A-Za-z0-9]+-[0-9]+$` |
| `convention.prefix` | Project id prefix strip | `Project ` |
| `selection.param` | Authorize parameter name | `project` |
| `claim.name` | Emitted claim name | `project` |
| `entitlement.attribute` | Cached user attribute key | `projectEntitlement` |

The convention regex is the D-019 "fail loud on unrecognized names" mechanism:
**only convention-conforming groups can ever yield an entitlement value or a claim**;
anything else is invisible by construction (recorded at debug level, never emitted).

## Non-goals

- No per-project configuration of any kind (scale ruling — hundreds to thousands of
  churning projects).
- No application permissions / client-credentials Graph access (the user's delegated
  token only).
- No roles: the entitlement bridge touches no role mapping — the config repo's HARD
  RULE (`roles` claim = dial roles only) is structural here.
- No changes to the existing job-title/photo mappers.

## Test plan

- **Unit** (mirror the existing per-class conventions): regex/prefix parsing; mode
  detection (null vs populated displayName, mixed batch); entitlement
  serialization/de-dup; selection validation (member/non-member/malformed/absent
  param); authenticator param capture (present/absent/multiple values — first wins,
  logged); protocol-mapper emission matrix (selection × entitlement × token type);
  failure semantics (Graph error → previous/empty entitlement).
- **Integration = the rig** (not in-repo): the config repo's `specs/03-verification.md`
  is the end-to-end test (one real corporate browser login + silent round-2); its
  checks P2-V1..P2-V8 are this extension's acceptance criteria. Nothing here duplicates
  them.

## Build & deployment

- JDK 17+, Gradle 8+ — `./gradlew clean build check` (checkstyle runs as part of
  `check`); artifact `build/libs/aidial-keycloak-mappers-<version>.jar`.
- Deployment: jar into Keycloak's `providers` directory + restart. Rig: a compose
  volume mount (the flash implementation round wires it; recorded in the config repo's
  manifest).
- Supply-chain: this repo's `trivy.yaml` / `.trivyignore` conventions apply to the
  dependency set the new code pulls in (prefer zero new dependencies — plain JDK
  `HttpClient` + hand-rolled JSON handling, matching the existing code's minimalism).
