# PostgreSQL Instance Management Platform

A local development control plane for managing isolated PostgreSQL instances.
This project is being implemented in milestones; the current foundation
provides a Spring Boot application and a separate PostgreSQL metadata database.

## Local development

Requirements: Java 21 and Docker with the Compose plugin. The Maven wrapper
downloads the pinned Maven version on first use.

1. Copy `.env.example` to `.env` and replace the example password with a local
   development password.
2. Start the metadata database with `docker compose up -d metadata-db`.
3. Run the application with `.\mvnw.cmd spring-boot:run`.
4. Check `http://127.0.0.1:8080/actuator/health`.

Run the test suite with `.\mvnw.cmd -B test`.

The database data is stored in the Compose-managed `metadata-data` volume. The
application and metadata database bind to localhost by default. Managed
PostgreSQL containers will be a separate resource pool and will not use the
metadata database.

## Architecture and security boundary

The backend is a single Java 21 / Spring Boot application. API, persistence,
security, and background worker responsibilities will be separated into
packages. PostgreSQL stores platform metadata and durable work; Flyway applies
schema migrations. The pinned PostgreSQL image is `postgres:17.6`.

Docker access is privileged: anyone able to control the Docker daemon may
control containers and host resources. The initial deployment is intended only
for a trusted local development environment, not for untrusted users or
internet-facing production use. Never commit `.env` or use real credentials in
`.env.example`.

Application roles (administrator, operator, viewer) govern use of this platform
and are distinct from PostgreSQL roles and privileges inside managed databases.
Capabilities, setup details, limitations, and validation status will be
expanded as milestones are completed.
