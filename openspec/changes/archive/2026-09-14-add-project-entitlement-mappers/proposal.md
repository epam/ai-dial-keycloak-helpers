# Proposal: add-project-entitlement-mappers

## Why

A consumer client needs a **server-validated, singular `project` claim** in
Keycloak-issued tokens: the user's entitled projects — Entra ID security groups
whose display names follow the realm's configured naming convention — become the
entitlement, and the session's project is a **client selection** (a custom token
request parameter) validated at every mint against that Graph-fetched
entitlement.

Design constraints that shaped the change:

- **Zero per-project configuration** — the naming convention is the only
  configuration; anything not matching it is invisible by construction.
- **Zero application permissions** — the Graph calls run with the user's own
  brokered token (delegated `User.Read`).
- **Fail closed everywhere** — a broken premise (a user-editable entitlement
  attribute, a cache-freezing sync mode, a stale cache, an unconfigured
  convention, an unverifiable membership) never yields a claim; it yields an
  ERROR log and a well-formed, claim-less token.
- **No role mapping** — the `roles` claim vocabulary is untouched; project
  transport is completely separate from role transport.

## What Changes

Two matched mappers added to the extension:

1. **`Entra Project Entitlement`** (IdP mapper) — at every brokered login,
   fetches the user's project groups from Microsoft Graph and caches the
   entitlement (plus a fetch timestamp) on the Keycloak user.
2. **`Project Selection (OIDC Claim)`** (protocol mapper) — at every token
   mint, reads the request's `project` parameter and emits the singular
   `project` claim only when the selection is within the cached entitlement;
   silently emits nothing otherwise.

Out of scope:

- No per-project configuration of any kind (the convention is the config).
- No application-permission / client-credentials Graph access.
- No roles: the entitlement bridge touches no role mapping.
- No changes to the existing job-title/photo mappers.
- End-to-end verification of a full adopting system (broker, clients, resource
  servers) is the adopting system's concern, not this extension's spec.

## Design history note

An earlier design carried the selection in a client-session note captured by a
required authenticator execution. It was retired during development: the note
rode a client-session record reused per (user, client), so a session whose
selection differed from the first one's could re-mint a foreign, still-entitled
claim. The state was removed, not repaired — the request parameter is the only
selection source, and no IdP session state is read or written on the mint path.
The retired design is preserved in the repository's git history.
