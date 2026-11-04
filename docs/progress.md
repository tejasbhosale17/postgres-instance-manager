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
- **Limitations:** No instance creation or worker flow exists yet. The default
  PostgreSQL 17.6 Testcontainers path was not exercised because the Docker
  Desktop engine is stopped.
- **Next:** Milestone 2 — asynchronous Docker-backed provisioning. This requires
  a running Docker engine for the mandated integration verification.
