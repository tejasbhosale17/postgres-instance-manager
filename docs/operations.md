# Operations and security

This application is a trusted-local-development control plane, not a
production multi-tenant service. It has no TLS termination, network isolation
or Docker-daemon sandbox. Keep the application, metadata database, Docker
daemon, and backup directory under the control of the same trusted operator.

## Configuration and startup

Copy `.env.example` to `.env`, replace every example password, and generate
separate random 32-byte Base64 keys for `JWT_SIGNING_KEY` and
`CREDENTIAL_ENCRYPTION_KEY`. Configure a bootstrap administrator username and
password together; the password must be 12–128 characters. Bootstrap
credentials are optional to the application, but an administrator is required
to manage roles and use administrator-only endpoints. If the bootstrap
username already exists as a non-administrator, startup fails instead of
silently changing that account.

| Setting | Default | Operational purpose |
|---|---|---|
| `SERVER_ADDRESS` | `127.0.0.1` | Application bind address. Keep loopback unless deployment controls are deliberately added. |
| `SERVER_PORT` | `8080` | HTTP port. |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5433/control_plane` | Metadata PostgreSQL JDBC URL. |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | Required | Metadata PostgreSQL credentials. |
| `JWT_SIGNING_KEY` | Required | Base64-encoded 32-byte HMAC signing key. Changing it invalidates issued tokens. |
| `CREDENTIAL_ENCRYPTION_KEY` | Required | Base64-encoded 32-byte AES key protecting instance passwords stored in operation payloads. |
| `BOOTSTRAP_ADMIN_USERNAME`, `BOOTSTRAP_ADMIN_PASSWORD` | Empty | Optional paired bootstrap administrator credentials. |
| `WORKER_POLL_DELAY_MS` | `500` | Worker queue poll interval. |
| `WORKER_LEASE_DURATION_SECONDS` | `120` | Operation lease duration; minimum 30 seconds. |
| `WORKER_LEASE_RENEWAL_MS` | `30000` | Active worker lease renewal interval. |
| `WORKER_RECOVERY_DELAY_MS` | `30000` | Expired-lease recovery interval. |
| `WORKER_RECONCILE_DELAY_MS` | `60000` | Managed-instance reconciliation interval. |
| `BACKUP_DIRECTORY` | `./backups` | Application-host directory for logical backup archives. |

Compose reads `.env` for `POSTGRES_DB`, `POSTGRES_USER`, and
`POSTGRES_PASSWORD`; the metadata database is published only on
`127.0.0.1:5433` and persists in the `metadata-data` volume. Start the database
with `docker compose up -d metadata-db`, then run the application with
`.\mvnw.cmd spring-boot:run`. Flyway migrations apply on startup. To stop the
metadata service without deleting its volume, run `docker compose stop
metadata-db`. Avoid `docker compose down -v`: removing the volume deletes
control-plane users, instance records, operations, audit history, and backup
metadata.

## Health, workers, and incident checks

- Check `GET /actuator/health` for the overall application, metadata database,
  and Docker health result. The public response intentionally omits component
  details.
- Authenticated administrators can query `/actuator/metrics` and
  `/actuator/info`. Metrics are in-process and reset when the application
  restarts.
- Poll the operation ID returned by a `202 Accepted` response at
  `GET /api/operations/{operationId}`. Operations are asynchronous and can
  finish as `SUCCEEDED` or `FAILED`; inspect `errorCode` on failure.
- The worker retries retryable operation failures at most three times with
  exponential backoff. Expired leases are recovered. Reconciliation fixes
  observed READY/STOPPED drift and marks missing containers failed; it does
  not recreate containers or remove Docker resources.
- Use the response `X-Correlation-ID` when searching application logs and
  activity records. Never include tokens, passwords, encryption keys, or
  backup contents in incident tickets or logs.

## Backups and recovery

Logical backups use PostgreSQL custom-format archives in
`BACKUP_DIRECTORY/<owner-id>/<backup-id>.dump`. The application records each
archive's byte count and SHA-256 checksum. Protect this directory with host
permissions and a separate, tested storage backup; the archive contains
plaintext database contents. There is no automated retention, archive
encryption, off-host replication, or cleanup job. Decide and execute retention
manually, taking care not to remove files for backups that must remain
restorable.

Restore requires `confirmDataReplacement: true`. It checks the checksum and
archive structure, restores into a staging database in a single transaction,
verifies a query against that database, then swaps it with the target's
`postgres` database. A failed stage leaves the target database in place; poll
the restore operation and verify the target before retrying. Backups remain
after deleting an instance, but restoration still requires an existing
eligible managed target.

Keep `CREDENTIAL_ENCRYPTION_KEY` available in a protected secrets store and
include it in access-controlled disaster-recovery material: existing managed
instance credentials cannot be decrypted for start, backup, or restore if this
key is lost. No in-place encryption-key rotation workflow is provided. Preserve
`JWT_SIGNING_KEY` separately; replacing it invalidates all existing access
tokens. Restore metadata PostgreSQL and the logical archive directory from
coordinated, access-controlled backups; database metadata alone does not
contain archive files or the encryption key.

## Security checklist

- Bind to loopback and use only with trusted local users. Anyone with access to
  the Docker daemon can control containers and host resources.
- Use unique random signing and encryption keys and distinct database/admin
  passwords. Do not put real values in source control, issue text, or demo
  commands.
- Restrict filesystem access to `.env`, metadata backups, and `BACKUP_DIRECTORY`.
- Treat logical archives as sensitive plaintext. Verify archive copies before
  deleting the originals and apply a documented retention policy.
- Remember that managed instances currently use the PostgreSQL `postgres`
  database user. The application role system is not a substitute for
  per-database isolation.
- Review `README.md` for the API surface and `docs/demo.md` for the disposable
  local demonstration.
