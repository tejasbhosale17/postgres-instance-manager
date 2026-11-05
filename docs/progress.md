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

## Milestone 3 — Instance lifecycle and data deletion

- **Status:** Implemented; live Docker integration verification completed.
- **Completed:** Added asynchronous start, stop, restart, and delete endpoints
  with state-checked transitions and owner/admin authorization. Start and
  restart wait until PostgreSQL accepts authenticated connections. Deletion
  requires `confirmDataDeletion: true`, removes only Docker resources bearing
  the instance's expected ownership labels, and records a `DELETED` metadata
  tombstone while allowing the owner to reuse the name.
- **Validation:** The Docker-backed integration test provisions an instance,
  verifies stop/start/restart, confirms existing database data survives the
  lifecycle transitions, rejects an invalid state transition and an unconfirmed
  delete, and verifies confirmed deletion removes the managed container and
  volume.
- **Limitations:** Durable operation leases, bounded retries, recovery, and
  reconciliation remain in Milestone 4.
- **Next:** Milestone 4 — idempotency, concurrent claims, leases, bounded
  retries, recovery, and reconciliation.

## Milestone 4 — Idempotency and worker reliability

- **Status:** Implemented; PostgreSQL and Docker integration verification
  completed.
- **Completed:** Added optional owner-scoped `Idempotency-Key` support for
  create and lifecycle requests, with transaction-scoped advisory locks and
  keyed request fingerprints. Queue claims use `FOR UPDATE SKIP LOCKED`, a
  unique lease token, worker identity, and renewable leases. Failures are
  retried with bounded exponential backoff and a three-attempt maximum;
  expired leases are recovered, and terminal failures restore the prior
  instance state. Reconciliation checks stable metadata against Docker state,
  corrects READY/STOPPED drift, and marks missing containers failed without
  deleting resources.
- **Validation:** Tests verify duplicate create/lifecycle requests return the
  original operation, key reuse with a different request returns HTTP 409,
  two concurrent PostgreSQL claimers cannot claim the same job, expired leases
  retry and then fail at the attempt limit, and Docker-backed reconciliation
  observes an externally stopped managed instance. `.\mvnw.cmd -B test` passed
  with all 13 tests executed (0 failures, 0 errors, 0 skipped).
- **Limitations:** Reconciliation reports missing containers as `FAILED`; it
  does not automatically recreate Docker resources. Retention and key-rotation
  procedures remain operational concerns.
- **Next:** Milestone 5 — health, metrics, correlation IDs, audit events, and
  activity views.

## Milestone 5 — Operational observability and activity

- **Status:** Implemented; integration verification completed.
- **Completed:** Added a Docker health indicator while retaining the
  PostgreSQL health check, with public health output limited to overall status.
  Added operation queue-state gauges, accepted/completed counters, and worker
  duration metrics behind administrator-protected Actuator endpoints.
  Requests now receive a validated or generated correlation ID in the response
  header, error body, application logs, persisted operation, and audit trail.
  Instance operation requests and asynchronous outcomes are audited without
  recording credentials or payloads. Added a bounded, owner-scoped activity
  endpoint; administrators can view platform-wide activity.
- **Validation:** `.\mvnw.cmd -B test` passed with all 15 tests executed,
  including correlation propagation, activity ownership, public health output,
  Actuator access controls, and the existing Docker-backed lifecycle suite.
- **Limitations:** Metrics are in-process and reset on restart; durable audit
  retention/export remains an operational responsibility.
- **Next:** Milestone 6 — asynchronous logical backup and verified restore.
