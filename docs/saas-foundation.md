# SaaS identity and tenancy foundation

KubeSpawn v0.7 changes the product boundary from a single trusted local developer to persisted SaaS identities and tenants. Deployment is deliberately deferred until the application authorization, quota, operations, and recovery gates are complete.

## Identity model

An authenticated principal is not itself authorization. KubeSpawn resolves a principal in this order:

1. The external provider supplies an immutable issuer and subject.
2. `user_identities` maps that pair to an `app_users` record.
3. An active `tenant_memberships` record grants access to one tenant.
4. The membership role grants tenant capabilities; `platform_role=ADMIN` separately grants cross-tenant administration.

Unknown or suspended identities fail closed. Email is profile data, not an identity key. The local development token maps to one seeded persisted platform administrator and must never be used as a production authentication mechanism.

## Roles

| Role | Intended capability |
|---|---|
| `VIEWER` | Read tenant environments, builds, progress, and audit history |
| `DEVELOPER` | Viewer access plus create and manage permitted preview environments |
| `MAINTAINER` | Developer access plus repository/project policy and wider lifecycle operations |
| `TENANT_ADMIN` | Maintainer access plus membership and tenant policy administration |
| Platform `ADMIN` | Cross-tenant user provisioning and platform operations |

The role model is persisted now. Tenant-filtered inventory and platform-admin checks are enforced now. Lifecycle mutation enforcement is the next milestone and remains unavailable outside the local development profile until it is complete.

## Authentication modes

- `local`: generated bearer credential used by the local development proxy. It resolves to the persisted bootstrap administrator.
- `saas`: OAuth2 authorization-code login against an external OIDC provider. Browser state uses a secure HttpOnly session cookie, session-fixation protection, and cookie/header CSRF validation.
- default resource server: scoped JWT bearer access for machine clients and APIs.

The browser session is separate from preview-route sessions. A control-plane login must not automatically become an unrestricted preview credential.

## Implemented product surfaces

- `GET /api/v1/me` and `PATCH /api/v1/me` for the current persisted profile.
- Platform-admin overview, user directory, external identity provisioning, membership role changes, and suspension.
- Profile and Admin portal areas.
- Tenant-scoped environment inventory.
- Audit events for profile and administrator changes.

## Required before deployment

1. Attach repositories and lifecycle records to explicit tenants and projects.
2. Enforce membership roles on every lifecycle, GitHub, build, audit, and preview-session operation.
3. Implement tenant/project quotas, concurrency limits, usage records, and retention policy.
4. Complete invitation/activation and selected-tenant flows, plus end-to-end browser OIDC tests.
5. Complete production preview SSO/session exchange, DNS/TLS, secret management, restore, observability, and cleanup recovery.
6. Run an internal pilot and security review.
7. Deploy only after the preceding gates pass.

This document describes implemented boundaries and remaining gates; it is not a production-readiness claim.
