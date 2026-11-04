# Implementation Progress

## Milestone 0 — Project foundation

- **Status:** Implemented; local startup verified.
- **Completed:** Java 21 / Spring Boot 3.5.6 project, pinned Maven 3.9.15 wrapper,
  configuration with credentials externalized to ignored `.env`, localhost
  metadata PostgreSQL Compose service (`postgres:17.6`), Flyway V1 migration,
  Docker API client dependencies, and architecture/setup documentation.
- **Validation:** The packaged application started against a temporary local
  PostgreSQL 18 instance, applied Flyway V1, and returned `UP` from
  `/actuator/health`. Testcontainers-backed tests are configured for PostgreSQL
  17.6; this environment used the external-database test setting with a
  temporary PostgreSQL 18 instance because Docker Desktop is stopped.
- **Limitations:** Container-backed tests and managed-instance validation
  require a running Docker engine.
- **Next:** Milestone 1 — authentication and metadata APIs.

## Milestone 1 — Authentication and metadata APIs

- **Status:** Implemented and validated against a temporary PostgreSQL 18
  database.
- **Completed:** JWT authentication, PBKDF2 password hashing, viewer-only
  registration, externally bootstrapped administrator, role assignment with
  self-change/final-admin protections, owned instance/operation reads, database
  constraints, structured validation/authentication errors, and audit events
  for administrative role changes.
- **Validation:** `.\mvnw.cmd -B test` passed with all 5 tests executed (0
  skipped) against an isolated temporary PostgreSQL database. The tests cover
  Flyway startup, registration privilege boundaries, password hashing and
  login, owner isolation, role authorization, and audit events. Live HTTP checks
  also verified cross-owner 404 behavior and administrative RBAC.
- **Limitations at completion:** Instance creation and worker processing were
  added in Milestone 2.
- **Next:** Milestone 2 — asynchronous Docker-backed provisioning.

## Milestone 2 — Asynchronous Docker-backed provisioning

- **Status:** Implemented and validated against Docker Engine 29.9.2.
- **Completed:** Authenticated instance creation returns HTTP 202 and creates a
  durable queued operation. A scheduled worker claims queued operations with
  PostgreSQL `FOR UPDATE SKIP LOCKED`, decrypts the AES-256-GCM-protected
  database password, and provisions a labeled PostgreSQL 17.6 container with a
  named data volume and a host port bound to localhost. The worker verifies
  readiness over JDBC and records the instance and operation results.
- **Validation:** `.\mvnw.cmd -B test` passed with all 9 tests executed (0
  failures, 0 errors, 0 skipped), including a live Docker-backed provisioning
  test that verifies operation completion, Docker ownership labels, the
  persistent volume mount, password secrecy in API/metadata, and a real
  database connection with read/write verification.
- **Limitations:** Lifecycle operations and explicit data deletion,
  idempotency, job leases/retries/recovery/reconciliation, and backup/restore
  are deferred to later milestones.
- **Next:** Milestone 3 — instance lifecycle operations and explicit
  persistent-data deletion.
