# diasoft-registry

Source-of-truth Spring Boot service for diploma imports, diploma lifecycle management, student share-link issuance, audit logging, and outbox publication.

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
- stores import files in S3-compatible object storage
- processes rows asynchronously
- records stable validation errors per row
- publishes domain events through transactional outbox

## Run

```bash
./gradlew bootRun
```

## Test

```bash
./gradlew test
```
