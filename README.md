# diasoft-registry

Source-of-truth Spring Boot service for diploma imports, diploma lifecycle management, student share-link issuance, audit logging, outbox publication, and internal BFF support for `diasoft-gateway`.

## Components

- `registry-api` — internal REST API
- `registry-import-worker` — asynchronous import processing
- `registry-outbox-publisher` — Kafka outbox delivery

## Stack

- Java 21
- Spring Boot 3
- PostgreSQL
- Kafka
- Flyway
- OIDC
- S3-compatible object storage

## Internal API

- `GET /api/v1/ping`
- `GET /api/v1/me`
- `GET /api/v1/universities`
- `POST /api/v1/universities/{id}/imports`
- `GET /api/v1/universities/{id}/imports`
- `GET /api/v1/imports/{id}`
- `GET /api/v1/imports/{id}/errors`
- `GET /api/v1/university/diplomas`
- `GET /api/v1/university/diplomas/{id}`
- `POST /api/v1/university/diplomas/{id}/revoke`
- `GET /api/v1/student/diplomas`
- `GET /api/v1/student/diplomas/{id}`
- `POST /api/v1/student/diplomas/{id}/share-links`

## Gateway internal API

For the new frontend handoff, `diasoft-gateway` talks to `diasoft-registry` through a dedicated service-auth layer:

- `GET /internal/gateway/university/diplomas`
- `POST /internal/gateway/university/imports`
- `GET /internal/gateway/university/imports/{jobId}`
- `GET /internal/gateway/university/imports/{jobId}/errors`
- `POST /internal/gateway/university/diplomas/{id}/revoke`
- `GET /internal/gateway/university/diplomas/{id}/qr`
- `GET /internal/gateway/student/diploma`
- `POST /internal/gateway/student/share-link`
- `DELETE /internal/gateway/student/share-link/{token}`

These routes are protected by a dedicated bearer token and do not replace the current OIDC-scoped registry API used by `diasoft-web`.

## Swagger / OpenAPI

Canonical platform Swagger now lives in `diasoft-gateway/api/openapi/openapi.yaml`.
This repository no longer keeps a separate local OpenAPI yaml file.

## Runtime modes

The same image can run in three modes using `APP_RUNTIME_MODE`:

- `api`
- `import-worker`
- `outbox-publisher`

## Import flow

- accepts CSV and XLSX files
- supports legacy English headers and frontend handoff Russian headers
- stores import files in S3-compatible object storage
- processes rows asynchronously
- records stable validation errors per row
- publishes domain events through transactional outbox
- GitHub Actions validation, publish, and promotion workflow

## Run

```bash
./gradlew bootRun
```

## Test

```bash
./gradlew test
```

## CI/CD

- canonical CI runs in GitHub Actions
- images publish to:
  - `ghcr.io/ozzus/diasoft-registry/registry-api`
  - `ghcr.io/ozzus/diasoft-registry/registry-import-worker`
  - `ghcr.io/ozzus/diasoft-registry/registry-outbox-publisher`
- `platform-infra` promotion is done by GitHub-based direct commits for `dev` and pull requests for controlled promotion paths
