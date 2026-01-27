## Overview

This repository contains custom Keycloak mappers used by AI DIAL to enrich user information from external identity providers (currently Microsoft) and expose those attributes as OIDC token claims such as `job_title` and `picture`.

The main deliverable is a single Keycloak extension JAR that you drop into your Keycloak installation and configure via the admin console.

## Keycloak mappers

This project provides custom Keycloak extensions that:

- **Enrich user attributes** in Keycloak from external IdPs (currently Microsoft Graph).
- **Expose those attributes as OIDC token claims** so downstream services (like AI DIAL or AuthProxy) can use them.

The project produces a single JAR:

- **Artifact**: `aidial-keycloak-mappers-<version>.jar`

You typically drop this JAR into Keycloak's `providers` directory (or use the container image's recommended extension directory) and restart Keycloak.

### What the extension does at runtime

At a high level, the extension:

- Hooks into the **identity provider login flow** to call Microsoft Graph with the external access token and cache selected user attributes on the Keycloak user.
- Hooks into the **token mapping flow** to read those cached attributes and expose them as OIDC claims (for example `job_title` and `picture`) in access/ID tokens and UserInfo responses.

Configuration is done entirely via the Keycloak admin console as described below.

## Installation

1. **Build the JAR**:

   ```bash
   ./gradlew build
   ```

   The JAR is produced under:

   - `build/libs/*.jar`

2. **Copy into Keycloak**:

   For a standard Keycloak distribution:

   - Copy the JAR into the `providers` directory of your Keycloak installation (or the appropriate extensions directory for your distribution).

3. **Restart Keycloak** so it picks up the new providers.

After restart, the new mapper types will appear in the Keycloak admin console.

## Configuring the IdP mapper (Microsoft Graph enrichment)

This mapper is configured under **Identity Providers → \<your IdP\> → Mappers**.

- **Mapper type**: `Microsoft Entra ID User Attributes`

When enabled, on each login (or brokered user update) the mapper will:

1. Extract the external access token from the brokered context.
2. Call Microsoft Graph to fetch:
   - `displayName`
   - `jobTitle`
   - a small profile photo (48x48) when enabled
3. Store the following user attributes on the Keycloak user:
   - `jobTitle` (string)
   - `picture` (data URI, base64 encoded, e.g. `data:image/jpeg;base64,...`)

### IdP mapper configuration fields

In the mapper form you will see two custom configuration properties:

| Property key       | Label           | Description                                                                                     | Default |
|--------------------|-----------------|-------------------------------------------------------------------------------------------------|---------|
| `fetch.job.title`  | Fetch Job Title | When `true`, fetches job title from Microsoft Graph and stores it as user attribute            | `true`  |
| `fetch.photo`      | Fetch Photo     | When `true`, fetches a small profile photo and stores it as base64‑encoded `picture` attribute | `true`  |

You can disable either if you do not want to call the corresponding Microsoft Graph endpoints.

## Configuring the protocol mapper (token claims)

This mapper is configured under **Client Scopes** or **Clients → \<client\> → Client Scopes / Mappers**.

- **Mapper type**: `Cached User Attributes (OIDC Claims)`

It reads the cached attributes that were stored on the user during the identity‑provider login flow and injects them into the OIDC token when present.

### Protocol mapper configuration fields

The mapper reuses `MapperConfiguration` and exposes the same flags:

| Property key       | Label           | Effect on token                                                                   | Default |
|--------------------|-----------------|-----------------------------------------------------------------------------------|---------|
| `fetch.job.title`  | Fetch Job Title | When `true`, adds `job_title` claim to the token if the user has `jobTitle` attr | `true`  |
| `fetch.photo`      | Fetch Photo     | When `true`, adds `picture` claim to the token if the user has `picture` attr    | `true`  |

Additionally, standard OIDC mapper options decide in which tokens the claims appear (access token, ID token, UserInfo).

### Resulting claims

When enabled and data is present on the user, the mapper will populate:

| User attribute | Token claim | Example value                                 |
|----------------|-------------|-----------------------------------------------|
| `jobTitle`     | `job_title` | `"Senior Software Engineer"`                  |
| `picture`      | `picture`   | `"data:image/jpeg;base64,/9j/4AAQSkZJRgABAQ"`|

These claims can then be consumed by downstream services (e.g. AI DIAL / AuthProxy) for display, authorization decisions, or auditing.

## Typical end‑to‑end setup

1. **Install the extension** and restart Keycloak.
2. **Configure the Microsoft identity provider** in Keycloak (as usual).
3. Under **Identity Providers → \<your Microsoft IdP\> → Mappers**, add:
   - A mapper of type **`Microsoft Entra ID User Attributes`**.
   - Tune `Fetch Job Title` / `Fetch Photo` as needed.
4. Under **Client Scopes** (e.g. scope used by AI DIAL, often `dial`):
   - Add a mapper of type **`Cached User Attributes (OIDC Claims)`**.
   - Enable `Fetch Job Title` / `Fetch Photo`.
   - In the “Token” section, ensure the mapper is included in the tokens you need (access token / ID token / UserInfo).
5. Attach the client scope to your AI DIAL client (e.g. `chatbot-ui`) as a default or optional scope.

On the next login via Microsoft into your realm, Keycloak will:

1. Call Microsoft Graph using the external access token.
2. Cache relevant user attributes.
3. Inject selected attributes into the tokens according to your mapper configuration.

## Development

- **JDK**: OpenJDK 17+
- **Build tool**: Gradle 8+

### Build

```bash
./gradlew clean build check
```

### Test

```bash
./gradlew test
```

## License
Copyright (C) 2023 EPAM Systems

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
