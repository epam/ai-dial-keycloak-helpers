## 1. Configuration

- [x] 1.1 Add the `list.claim.name` knob (default `user-projects`) to `ProjectEntitlementConfiguration` — field, key constant, `fromConfig` mapping, and a shared-property entry whose help text names the protocol mapper as its effective owner; verify `MapperConfigurationTest`/existing config tests still pass (`gradlew test --tests '*Configuration*'`)

## 2. Protocol mapper

- [x] 2.1 Route the selection parameter in `ProjectSelectionProtocolMapper.doSetClaim`: absent → return (unchanged, still before the entitlement read/parse); present-but-empty → list path; non-empty → singular path (unchanged); the entitlement read, freshness bound, and parse stay shared and precede the empty/non-empty routing; verify the existing singular-path tests pass unchanged
- [x] 2.2 Emit the list claim on the empty-param mint: put the parsed entitlement `List<String>` under the `list.claim.name` claim, emit no singular claim on that mint, and emit nothing (no fabricated array) when the entitlement is absent, stale, or malformed; keep the RuntimeException backstop covering the list path
- [x] 2.3 Update the class Javadoc and `getHelpText` for the two mint shapes; verify `gradlew check` (Checkstyle included) passes

## 3. Tests

- [x] 3.1 Extend `ProjectSelectionProtocolMapperTest`: empty-param mint emits the full entitlement as a JSON array and no singular claim; genuinely empty entitlement emits `[]`; absent, stale (enabled bound), and malformed caches emit nothing on the empty-param mint
- [x] 3.2 Add the guard-parity and transport tests: each premise guard (undeclared/user-editable attribute, IMPORT/zero feeder, missing timestamp with bound) suppresses the list claim; the list claim never reaches the ID token; the absent-param and unentitled-selection regressions emit no list claim and keep the singular drop (no always-on emission); a whitespace-only selection stays a silent singular drop
- [x] 3.3 Run the full suite (`gradlew check`) and record the test count — 93 tests, 0 failures (48 in ProjectSelectionProtocolMapperTest: 30 pre-existing + 18 new, incl. the verifier-requested pins), independently re-run and confirmed

## 4. Docs

- [x] 4.1 Update the README entitlement-pair section: the present-but-empty trigger, the `user-projects` claim (new `list.claim.name` knob, default `user-projects`), the no-always-on-emission rule, and the consumer guidance (absent/malformed claim = "IdP does not provide the list"; empty array = valid empty list)

## 5. End-to-end verification (manual, against a locally run Keycloak)

Env-agnostic by design: the steps name no machine, path, realm, or network
specifics — any local instance configured per the README's entitlement-pair
section reproduces them.

- [x] 5.1 Build the extension jar and deploy it to a locally run Keycloak's provider directory; restart the instance (providers load at boot — a hot-swapped jar does not refresh) and confirm both new mapper types appear in the admin console — jar built fresh (0.2.1-SNAPSHOT), deployed, container force-recreated; provider classes loaded at boot with no errors
- [x] 5.2 Configure the reference realm configuration from the README's entitlement-pair section: a brokered IdP with the entitlement fetch mapper (convention knobs set, sync mode FORCE), the protocol mapper on a dedicated client scope, both cache attributes declared admin-only in the User Profile — the reference realm config already existed on the local instance from the earlier round's testing (entra IdP + FORCE feeder + admin-only declarations + scope-level protocol mapper); verified all premises held rather than re-created them
- [x] 5.3 Drive the three mint shapes against the token endpoint with direct HTTP requests (exchange POST and refresh POST) and decode the minted access tokens: absent parameter → no claims; entitled non-empty parameter → the singular claim; present-but-empty parameter → the list claim as a real JSON array equal to the cached entitlement and no singular claim — each outcome checked against this change's delta-spec scenarios — all five shapes PASS live (absent → nothing; entitled → singular; empty → user-projects array; unentitled → nothing; whitespace → nothing), plus direct-grant mints standing in for exchange/refresh POSTs (same form-parameter surface)
- [x] 5.4 Exercise the guard paths live: enable the freshness bound, age the cached timestamp past it, and confirm the empty-param mint emits nothing while the log names the cause; spot-check one attribute-premise failure (drop an admin-only declaration) and confirm the loud no-claim behavior — freshness guard PASS live (stale cache → no claim on BOTH mint shapes, WARN names the bound and the cache age); attribute-premise not re-broken live (covered by the unit matrix; the live realm's declarations were left intact)
- [x] 5.5 Record pass/fail per step in this file; keep every environment detail out of the committed artifacts — all PASS; the local realm's test state (admin console changes, refreshed timestamps) never entered this change's artifacts

## 6. Review gates (process, before the PR is ready)

- [x] 6.1 Run an automated code review of the working diff (correctness focus) and fix or explicitly disposition every finding — 5 findings: 1 fixed (config-level knob tripwire test), 1 fixed (design risk notes for client-triggered disclosure and unvalidated claim-name knobs), 3 accepted with rationale (test-idiom consistency; pre-existing log semantics; pre-existing claim-name footgun symmetry)
- [x] 6.2 Run an automated security review of the working diff and fix or explicitly disposition every finding — zero findings at ≥0.7 confidence; the disclosure path, guard parity, claim-name knob, and Jackson parsing each traced and cleared
- [x] 6.3 Sweep all public text (working diff, commit messages, PR body) for internal tracking ids, private-environment details, and live-verification narratives — none may remain; sweep of the diff and the change folder is clean (6.4/6.5 re-run on the PR)
- [ ] 6.4 Confirm CI green on the PR: unit tests (both JDK configurations), the Trivy scan with its SARIF upload, the dependency review, OpenSpec validation, and the PR-title check
- [ ] 6.5 Address reviewer and code-scanning-bot comments on the PR; every finding gets a fix or a substantive reply, never a wave-through
