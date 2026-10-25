# URL Shortener

A URL shortener for the rebuy development challenge, built with Java 21,
Spring Boot 4.1, Spring Data JPA, PostgreSQL, and Flyway.

## Current implementation

This first increment provides the application bootstrap, database configuration,
and a PostgreSQL-backed smoke test. Link creation, redirects, and the frontend
will be added in the following commits.

## Requirements

- JDK 21 or later. The project targets Java 21.
- Docker with Docker Compose, running before starting the database or tests.
- Maven is supplied by the Maven Wrapper. The first build needs internet access
  to download Maven and dependencies.

## Run locally

Start PostgreSQL and wait for its health check:

```sh
docker compose up -d --wait postgres
```

Start the application:

```sh
./mvnw spring-boot:run
```

Verify the application and database are healthy:

```sh
curl --fail http://localhost:8080/actuator/health
```

A healthy response has HTTP status `200` and includes `"status":"UP"` in its
JSON body. The root page currently returns `404` until the frontend is implemented.

Stop the application with `Ctrl+C`. Stop PostgreSQL with:

```sh
docker compose down
```

PostgreSQL data remains in the named Docker volume between restarts.

## Local configuration

Database settings are defined directly in `src/main/resources/application.yaml`
and `compose.yaml`. The application uses Spring Boot's default HTTP port, 8080.

| Setting | Value |
| --- | --- |
| Application address | `http://localhost:8080` |
| Database URL | `jdbc:postgresql://localhost:5432/url_shortener` |
| Database user | `url_shortener` |
| Database password | `url_shortener` |

## Build and test

```sh
./mvnw verify
```

The smoke test starts an isolated PostgreSQL 16 container on a random port.
It does not use the Compose database. It verifies application startup, database
connectivity, Flyway initialization, and the HTTP health endpoint. Docker must
be available; the test is not silently skipped when it is unavailable.

To run the packaged application after a successful build:

```sh
java -jar target/url-shortener-0.0.1-SNAPSHOT.jar
```

Start the Compose database first, as described above.

## Initial technical decisions

- One Spring Boot application will serve the API, redirects, and static frontend.
- Spring Data JPA provides persistence; Flyway owns schema changes. Hibernate
  validates the schema and does not create or update it automatically.
- Open Session in View is disabled so persistence contexts remain within explicit
  transaction boundaries.
- The migrations directory is intentionally empty in this increment. The link
  table will be introduced with its entity in the next commit.
- Only the Actuator health endpoint is exposed, without component details.
- PostgreSQL is bound to localhost for local development.
