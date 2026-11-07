# PostgreSQL Instance Management Platform

A local development control plane for managing isolated PostgreSQL instances.
The platform provides authenticated metadata APIs and asynchronous
Docker-backed PostgreSQL provisioning.

## Local development

Requirements: Java 21 and Docker with the Compose plugin. The Maven wrapper
downloads the pinned Maven version on first use.

1. Copy `.env.example` to `.env`. Set distinct local database and bootstrap
   administrator passwords. Generate two separate random keys containing 32
   bytes each, one for JWT signing and one for credential encryption. In
   PowerShell, run this once for each key:

   ```powershell
   $bytes = New-Object byte[] 32
   $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
   $rng.GetBytes($bytes)
   [Convert]::ToBase64String($bytes)
   $rng.Dispose()
   ```

   Put the first Base64 value in `JWT_SIGNING_KEY` and the second in
   `CREDENTIAL_ENCRYPTION_KEY`. Set the bootstrap username and password; the
   password must contain at least 12 characters. `.env` is ignored by Git and
   is read by both Compose and Spring.
2. Start the metadata database with `docker compose up -d metadata-db`.
3. Run the application with `.\mvnw.cmd spring-boot:run`.
4. Check `http://127.0.0.1:8080/actuator/health`.

Run the test suite with `.\mvnw.cmd -B test`. Tests use Testcontainers to start
disposable PostgreSQL 17.6 databases and the provisioning integration test also
requires a running Docker engine. Alternatively, set `TEST_DATABASE_URL`,
`TEST_DATABASE_USERNAME`, and `TEST_DATABASE_PASSWORD` to use an isolated,
disposable PostgreSQL database for tests that do not require Docker. The tests
apply Flyway migrations and create test users and records in that database.

Metadata is stored in the Compose-managed `metadata-data` volume. The
application and metadata database bind to localhost by default. Each managed
PostgreSQL container uses its own labeled Docker volume, a randomly assigned
host port bound only to `127.0.0.1`, and the pinned `postgres:17.6` image.

## Architecture and security boundary

The backend is a single Java 21 / Spring Boot 3.5.6 application. PostgreSQL
stores platform metadata and durable operations; Flyway applies schema
migrations. A scheduled worker claims queued operations from PostgreSQL and
provisions managed instances through Docker. Each claim has a renewable,
token-fenced lease; abandoned jobs are retried with bounded exponential
backoff, then failed after three attempts. A periodic reconciler compares
stable instance records with labeled Docker containers and corrects
READY/STOPPED drift or marks missing containers failed. It does not recreate
containers or delete resources during reconciliation.

Database passwords are encrypted with AES-256-GCM before being persisted in
operation payloads; the encryption key must be supplied externally as
`CREDENTIAL_ENCRYPTION_KEY`. Optional `Idempotency-Key` headers on instance
creation and lifecycle requests allow clients to safely retry requests: the
same owner, key, and request returns the original operation, while reusing a
key for a different request returns HTTP 409.

Docker access is privileged: anyone able to control the Docker daemon may
control containers and host resources. The initial deployment is intended only
for a trusted local development environment, not for untrusted users or
internet-facing production use. Never commit `.env` or use real credentials in
`.env.example`.

Application roles (administrator, operator, viewer) govern use of this platform
and are distinct from PostgreSQL roles and privileges inside managed databases.
Managed instances currently use the `postgres` database user. Lease and
reconciliation schedules can be tuned with `WORKER_LEASE_DURATION_SECONDS`,
`WORKER_LEASE_RENEWAL_MS`, `WORKER_RECOVERY_DELAY_MS`, and
`WORKER_RECONCILE_DELAY_MS`; their defaults are 120 seconds, 30 seconds,
30 seconds, and 60 seconds respectively. Logical backup archives are written
under `BACKUP_DIRECTORY` (default `./backups`, relative to the application
working directory) and persist independently of instance deletion. Backups
contain database contents in plaintext; restrict filesystem access and protect
the directory with appropriate host storage controls. This milestone does not
provide backup encryption, remote replication, or automatic retention.

## Authentication and API

Public registration always creates a `VIEWER`; request fields cannot assign a
role. `POST /api/auth/login` issues a 15-minute JWT, and authenticated requests
send it as `Authorization: Bearer <token>`. Administrators are bootstrapped from
the external `BOOTSTRAP_ADMIN_USERNAME` and `BOOTSTRAP_ADMIN_PASSWORD` settings.
Administrators can list users and change another user's role with
`PATCH /api/admin/users/{userId}/role`; these changes are audited. Admins cannot
change their own role, and the final enabled administrator cannot be demoted.
Passwords are stored as PBKDF2 hashes. API responses omit password hashes.

| Method | Path | Access |
|---|---|---|
| `POST` | `/api/auth/register` | Public; creates a viewer |
| `POST` | `/api/auth/login` | Public |
| `GET` | `/api/auth/me` | Authenticated |
| `POST` | `/api/instances` | Operator or administrator; queues provisioning |
| `POST` | `/api/instances/{id}/start` | Owning operator or administrator; queues start |
| `POST` | `/api/instances/{id}/stop` | Owning operator or administrator; queues stop |
| `POST` | `/api/instances/{id}/restart` | Owning operator or administrator; queues restart |
| `DELETE` | `/api/instances/{id}` | Owning operator or administrator; explicit data-deletion confirmation required |
| `GET` | `/api/instances` | Authenticated; own records, or all for administrators |
| `GET` | `/api/instances/{id}` | Owner or administrator |
| `GET` | `/api/operations/{id}` | Owner or administrator |
| `POST` | `/api/instances/{id}/backups` | Owning operator or administrator; queues a logical backup |
| `POST` | `/api/instances/{id}/restore` | Owning operator or administrator; queues a confirmed database replacement |
| `GET` | `/api/backups?limit=50` | Authenticated; own backups, or all for administrators |
| `GET` | `/api/backups/{backupId}` | Backup owner or administrator |
| `GET` | `/api/backups/{backupId}/content` | Backup owner or administrator; downloads the archive |
| `GET` | `/api/activity?limit=50` | Authenticated; own audit and operation activity, or all for administrators |
| `GET` | `/api/admin/users` | Administrator |
| `PATCH` | `/api/admin/users/{userId}/role` | Administrator |

Each response includes an `X-Correlation-ID`. A valid supplied value (1–64
letters, digits, dots, underscores, or hyphens) is reused; otherwise the
application generates a UUID. The ID is included in application log lines,
problem responses, queued operations, and related audit events. Health status
is public and exposes only the overall status; Actuator metrics and info require
administrator access. Metrics include HTTP request measurements, worker
operation durations, operation outcome counters, and operation queue-state
gauges.

Instance operation requests and asynchronous outcomes are recorded as audit
events. Activity is limited to 100 recent events per request (default 50).
Audit metadata contains operation type, instance ID, state, and safe error
codes only; it does not contain database passwords, encrypted credentials,
tokens, or operation payloads.

Create an instance by sending a name and a database password of at least 12
characters:

```powershell
$idempotencyKey = [guid]::NewGuid().ToString()
$accepted = Invoke-RestMethod -Method Post `
  -Uri http://127.0.0.1:8080/api/instances `
  -Headers @{ Authorization = "Bearer $token"; 'Idempotency-Key' = $idempotencyKey } `
  -ContentType 'application/json' `
  -Body '{"name":"dev-db","databasePassword":"<PASSWORD-OF-AT-LEAST-12-CHARACTERS>"}'

$accepted
```

The request returns HTTP `202 Accepted` with an operation ID and `QUEUED` state.
Poll `GET /api/operations/{operationId}` until it reaches `SUCCEEDED` or
`FAILED`, then retrieve the instance using `GET /api/instances/{instanceId}`.
The instance response includes its state, database username, and localhost host
port; the password is never returned. Non-administrators receive `404` when
requesting another owner's instance or operation, avoiding resource-existence
disclosure. Validation and API errors use `application/problem+json`.

The optional `Idempotency-Key` header is scoped to the authenticated owner and
supports up to 255 nonblank characters. Repeating the same request with the
same key returns the original operation ID. A different request with a
previously used key returns HTTP `409 Conflict`. If the header is omitted,
each request is treated as new.

Start, stop, and restart are also asynchronous and return an operation ID. A
start is valid only for a stopped instance, stop only for a ready instance, and
restart for a ready or stopped instance. Requests that do not match the current
state return `409 Conflict`.

Deleting an instance permanently removes its Docker container and PostgreSQL
data volume. It requires an explicit confirmation in the request body:

```powershell
Invoke-RestMethod -Method Delete `
  -Uri "http://127.0.0.1:8080/api/instances/$instanceId" `
  -Headers @{ Authorization = "Bearer $token" } `
  -ContentType 'application/json' `
  -Body '{"confirmDataDeletion":true}'
```

Deletion runs asynchronously; poll the returned operation as above. The
instance remains as a `DELETED` metadata record, and its name may be reused.
Omitting the confirmation or setting it to `false` is rejected with HTTP 400.

Create a backup with `POST /api/instances/{instanceId}/backups` while the
instance is `READY`; poll its operation to completion, then list backups or
retrieve the backup record to obtain its ID. Download the immutable custom
format archive from `/api/backups/{backupId}/content`. Restore it to a
`READY` managed instance with `POST /api/instances/{instanceId}/restore` and
body `{"backupId":"<BACKUP-ID>","confirmDataReplacement":true}`. The worker
validates the archive, restores it into a temporary database, verifies that
the restored database accepts a query, then replaces the target's `postgres`
database. The existing target remains intact if staging or verification fails.
Restore is asynchronous and requires explicit confirmation because it replaces
the target database contents. Backups remain available after deleting their
source instance and are stored locally until manually removed; keep the
configured directory backed up and apply an appropriate retention policy.

The application binds to localhost by default. Docker access is privileged:
anyone able to control the Docker daemon can control containers and host
resources. This initial deployment is intended only for a trusted local
development environment, not for untrusted users or internet-facing production
use.
