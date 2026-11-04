# PostgreSQL Instance Management Platform

A local development control plane for managing isolated PostgreSQL instances.
The current implementation provides authenticated user and metadata APIs; Docker
provisioning and lifecycle operations are planned for later milestones.

## Local development

Requirements: Java 21 and Docker with the Compose plugin. The Maven wrapper
downloads the pinned Maven version on first use.

1. Copy `.env.example` to `.env`. Set distinct local database and bootstrap
   administrator passwords, and generate a random JWT signing key containing at
   least 32 bytes. For example, in PowerShell:

   ```powershell
   $bytes = New-Object byte[] 32
   $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
   $rng.GetBytes($bytes)
   [Convert]::ToBase64String($bytes)
   $rng.Dispose()
   ```

   Put the resulting Base64 value in `JWT_SIGNING_KEY`. The bootstrap username
   and password must both be set; the password must contain at least 12
   characters. `.env` is ignored by Git and is read by both Compose and Spring.
2. Start the metadata database with `docker compose up -d metadata-db`.
3. Run the application with `.\mvnw.cmd spring-boot:run`.
4. Check `http://127.0.0.1:8080/actuator/health`.

Run the test suite with `.\mvnw.cmd -B test`. By default the integration tests
start PostgreSQL 17.6 with Testcontainers, so Docker must be running. To use an
isolated, disposable PostgreSQL database instead, set `TEST_DATABASE_URL`,
`TEST_DATABASE_USERNAME`, and `TEST_DATABASE_PASSWORD` before running the tests.
The tests apply Flyway migrations and create test users and records in that
database.

The database data is stored in the Compose-managed `metadata-data` volume. The
application and metadata database bind to localhost by default. Managed
PostgreSQL containers will be a separate resource pool and will not use the
metadata database.

## Architecture and security boundary

The backend is a single Java 21 / Spring Boot 3.5.6 application. PostgreSQL
stores platform metadata and durable work; Flyway applies schema migrations.
The pinned PostgreSQL image is `postgres:17.6`. Docker is used only for the
metadata database at this stage; managed-instance containers are not yet
implemented.

Docker access is privileged: anyone able to control the Docker daemon may
control containers and host resources. The initial deployment is intended only
for a trusted local development environment, not for untrusted users or
internet-facing production use. Never commit `.env` or use real credentials in
`.env.example`.

Application roles (administrator, operator, viewer) govern use of this platform
and are distinct from PostgreSQL roles and privileges inside managed databases.

## Authentication and API

Public registration always creates a `VIEWER`; request fields cannot assign a
role. `POST /api/auth/login` issues a 15-minute JWT, and authenticated requests
send it as `Authorization: Bearer <token>`. Administrators are bootstrapped from
the external `BOOTSTRAP_ADMIN_USERNAME` and `BOOTSTRAP_ADMIN_PASSWORD` settings.
Administrators can list users and change another user's role with
`PATCH /api/admin/users/{userId}/role`; these changes are audited. Admins cannot
change their own role, and the final enabled administrator cannot be demoted.
Passwords are stored as PBKDF2 hashes. API responses omit password hashes.

Available endpoints in this milestone:

| Method | Path | Access |
|---|---|---|
| `POST` | `/api/auth/register` | Public; creates a viewer |
| `POST` | `/api/auth/login` | Public |
| `GET` | `/api/auth/me` | Authenticated |
| `GET` | `/api/instances` | Authenticated; own records, or all for administrators |
| `GET` | `/api/instances/{id}` | Owner or administrator |
| `GET` | `/api/operations/{id}` | Owner or administrator |
| `GET` | `/api/admin/users` | Administrator |
| `PATCH` | `/api/admin/users/{userId}/role` | Administrator |

Non-administrators receive `404` when requesting another owner's instance or
operation, avoiding resource-existence disclosure. Instance creation,
asynchronous operations, and Docker lifecycle endpoints are not implemented
yet. Validation and API errors use `application/problem+json`.

For example, register a viewer and exchange credentials for a bearer token:

```powershell
curl.exe -X POST http://127.0.0.1:8080/api/auth/register `
  -H "Content-Type: application/json" `
  -d '{"username":"viewer1","password":"<PASSWORD-OF-AT-LEAST-12-CHARACTERS>"}'

$token = (Invoke-RestMethod -Method Post `
  -Uri http://127.0.0.1:8080/api/auth/login `
  -ContentType 'application/json' `
  -Body '{"username":"viewer1","password":"<PASSWORD-OF-AT-LEAST-12-CHARACTERS>"}').accessToken

Invoke-RestMethod -Uri http://127.0.0.1:8080/api/instances `
  -Headers @{ Authorization = "Bearer $token" }
```

The application binds to localhost by default. Docker access is privileged:
anyone able to control the Docker daemon can control containers and host
resources. This initial deployment is intended only for a trusted local
development environment, not for untrusted users or internet-facing production
use.
