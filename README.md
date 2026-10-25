# URL Shortener

A URL shortener for the rebuy development challenge, built with Java 21,
Spring Boot 4.1, Spring Data JPA, PostgreSQL, and Flyway.

## Features

- Four-character, case-sensitive Base62 codes (`0-9`, `A-Z`, `a-z`).
- An embedded URL form with copy/open actions and a JSON API.
- Persistent mappings in PostgreSQL and `302` redirects to the original URL.
- Reuse of an existing code for the same destination, including concurrent requests
  across application instances.

## Requirements

- JDK 21 or later. The project targets Java 21.
- Docker with Docker Compose for local PostgreSQL and integration tests.
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

Open [http://localhost:8080/](http://localhost:8080/), paste a full HTTP(S) URL,
and select **Shorten link**. The result can be copied or opened directly. If
clipboard access is unavailable, the link is selected for manual copying.
The form stops waiting after ten seconds and allows another attempt. A repeated
destination reuses the existing code, including retries after a browser timeout.

Verify the application and database are healthy:

```sh
curl --fail http://localhost:8080/actuator/health
```

A healthy response has HTTP status `200` and includes `"status":"UP"` in its
JSON body.

Stop the application with `Ctrl+C`. Stop PostgreSQL with:

```sh
docker compose down
```

PostgreSQL data remains in the named Docker volume between restarts.

## Local configuration

Database settings are defined directly in `src/main/resources/application.yaml`
and `compose.yaml`. The application listens on port 8080.

| Setting | Value |
| --- | --- |
| Application address | `http://localhost:8080` |
| Database URL | `jdbc:postgresql://localhost:5432/url_shortener` |
| Database user | `url_shortener` |
| Database password | `url_shortener` |
| Maximum encoded destination | 16 KiB (16,384 bytes) |
| Tomcat response header budget | 32 KiB |

## Create and follow a short link

Submit an absolute HTTP or HTTPS URL:

```sh
curl -i http://localhost:8080/api/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/products?category=books#recommendations"}'
```

The first request has status `201 Created`; an existing destination returns
`200 OK`. Both responses have a `Location` header containing the short URL and
a JSON body such as:

```json
{
  "code": "aB7x",
  "shortUrl": "http://localhost:8080/aB7x",
  "targetUrl": "https://example.com/products?category=books#recommendations"
}
```

Codes are generated randomly, so use the URL returned by your request. Inspect
its redirect without following it:

```sh
curl -i http://localhost:8080/aB7x
```

The response has status `302` and a `Location` header with the destination URL,
percent-encoded where needed, including its query string and fragment. Short-link
codes must contain exactly four alphanumeric characters. Unknown or malformed codes return `404`.
Codes are case-sensitive.

The destination must have valid URI syntax, an HTTP(S) scheme, and a host.
Surrounding whitespace is removed; encoded path, query, and fragment components
are preserved. International domain names must use their ASCII/Punycode form.
Invalid destinations, missing URLs, and malformed JSON return `400 Bad Request`.
Unpaired UTF-16 surrogates in JSON URL values are also rejected before URI encoding
or database access; valid supplementary Unicode characters remain supported.
Validation and code-allocation errors use `application/problem+json`.

The full destination, including query and fragment, must fit **16,384 bytes after
URL encoding**. Validation checks `URI.toASCIIString().length()` after trimming;
the result contains only ASCII, so its character count equals its byte count.
Unicode characters can expand into several percent-encoded characters. An
oversized destination returns `400` with the detail
`URL must not exceed 16384 bytes after URL encoding.` before database access or
code generation. The database keeps the trimmed original URL without rewriting it.

The same exact URL returns the existing code. Equality is checked after removing
surrounding whitespace; no normalization of case, trailing slashes, query order,
or fragments is performed. Different query strings or fragments are different
destinations. The absolute short URL uses the current application address, so
the demo ports return different origins while sharing the same code.

## Build and test

Run the fast unit tests without an application context, database, or Docker:

```sh
./mvnw -Dgroups=unit test
```

Unit tests use JUnit and Mockito. They cover URL syntax and encoded-size validation,
code format, bounded retries, database error classification, and response construction. They live in
`unit.service`, `unit.controller`, and `unit.exception`.

Run all tests and build the application:

```sh
./mvnw verify
```

Integration tests start an isolated PostgreSQL 16 container on a random port.
They do not use the Compose database. They verify application startup, Flyway
migrations, persistence constraints, creation, validation, redirects, collision
recovery, retry exhaustion, concurrent requests, and the HTTP health endpoint.
Real HTTP tests cover the 16,384-byte ASCII boundary through creation, reuse,
and a complete redirect; 16,385 bytes are rejected without inserting a row.
They also verify Cyrillic destinations, their encoded `Location` values, and
rejection when encoding makes the destination exceed the limit.
Collision tests replace the random generator with deterministic candidates while
using real PostgreSQL transactions and constraints. Concurrency checks send
requests to one application instance and verify both ordinary parallel creation
and a forced race for the same candidate code, including the resulting redirects.
`MultiInstanceIntegrationTest` starts two independent Spring application contexts
in one JVM against a shared test database. It verifies cross-instance reads,
parallel creation, a forced collision between the two generators, and simultaneous
creation of the same destination. For a shared destination, it also checks that
only the winning instance invokes the generator. The Docker demo below uses
separate JVM processes.
Docker must be available; tests are not silently skipped when it is unavailable.

Test packages separate application startup (`integration.application`), API and
redirects (`integration.controller`), persistence and migrations (`integration.db`),
collision recovery (`integration.service`), and multiple instances
(`integration.concurrency`). Shared HTTP clients, SQL fixtures, and concurrent
execution helpers live in `support`; PostgreSQL setup lives in `config`.
Integration tests use real commit/rollback boundaries rather than wrapping each scenario in
an enclosing test transaction.

To run the packaged application after a successful build:

```sh
java -jar target/url-shortener.jar
```

Start the Compose database first, as described above.

## Run two instances locally

Build the JAR, then start two application containers using the same PostgreSQL:

```sh
./mvnw verify
docker compose -f compose.multi.yaml up -d --build
```

`compose.multi.yaml` is a standalone entry point and reuses the PostgreSQL
service from `compose.yaml`. In IntelliJ IDEA, selecting only `compose.multi.yaml`
is sufficient. Use the project name `rebuy-url-shortener` to reuse the default
local database volume; a different project name gets a separate volume.
After Java changes, rebuild the JAR and the Docker image before running again.

Once the applications have started, check both health endpoints:

```sh
curl --fail --retry 30 --retry-all-errors --retry-delay 1 http://localhost:8081/actuator/health
curl --fail --retry 30 --retry-all-errors --retry-delay 1 http://localhost:8082/actuator/health
```

Compose waits for PostgreSQL's health check; application startup takes a few
seconds afterwards. The health commands retry startup connection errors so the
block can be run immediately after Compose. Both endpoints should return `"status":"UP"`.
The web form is available on either port. These are separate JVM processes with
independent generators, connection pools, and persistence contexts.

Create a link on the first instance:

```sh
curl -i http://localhost:8081/api/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/products?category=books#recommendations"}'
```

Take the returned code and request it on the second instance. For example, if
the response contains `"code":"aB7x"`:

```sh
curl -i http://localhost:8082/aB7x
```

The second instance returns `302` with the same destination. Repeat the original
creation request through the second instance:

```sh
curl -i http://localhost:8082/api/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/products?category=books#recommendations"}'
```

This returns `200 OK` and the same code. The absolute short URL uses the address
of the instance that handled the request, so the ports differ while the code and
destination remain the same. A destination already present before the demo also
returns `200` on the first instance.

Stop just the demo applications, leaving the database and its data available:

```sh
docker compose -f compose.multi.yaml stop app-1 app-2
```

The runtime image uses Java 21 and an unprivileged user. Docker changes only the
database hostname through a literal command-line argument; local application
settings remain in `application.yaml`.

## Technical decisions

### Application structure

One Spring Boot application serves the API, redirects, and static frontend.
PostgreSQL provides shared persistent storage for multiple application instances.

| Component | Responsibility |
| --- | --- |
| `controller.ShortLinkController` | HTTP requests, response DTOs, status codes, and redirects |
| `service.ShortLinkService` | URL validation, creation coordination, and bounded collision retries |
| `service.ShortLinkDbService` | Repository access, transaction boundaries, URL locking, code generation when needed, and persistence error translation |
| `service.ShortCodeGenerator` | Random four-character code candidates |
| `exception.ApiExceptionHandler` | Domain failures and Spring MVC validation errors as problem details |

### Code generation and collisions

`SecureRandom` selects each of the four Base62 characters independently. The
internal numeric row ID is separate from the public code. Randomness does not
guarantee uniqueness: `UNIQUE(code)` rejects a duplicate candidate, including
when different instances generate it simultaneously. No preliminary
code-existence query is needed. A database check enforces the code format.

Each request owns its output buffer, retry counter, and entity. The shared
`SecureRandom` is thread-safe, and Spring binds the persistence context to the
transaction's thread.

### Reusing a destination across instances

Creation follows this order:

1. Remove surrounding whitespace and validate the destination, including its
   encoded size, before database access.
2. Start a DB-service transaction and acquire a PostgreSQL transaction advisory
   lock using `hashtextextended(targetUrl, 0)` as its key.
3. Look up the complete destination URL in a separate query.
4. If found, return the stored mapping without generating a code (`200`).
5. Otherwise, generate a candidate, insert a fresh entity, and commit (`201`).

The lock protects the operation **check destination, then create mapping**.
Without it, two instances could both find no row and insert different codes for
the same URL; `UNIQUE(code)` would allow both. The lock works before any row exists
and is automatically released at commit or rollback. A Java `synchronized` block
would cover only one JVM. At `READ_COMMITTED`, the lookup after waiting for the
lock sees the preceding creator's committed row.

`target_url` has a nonunique hash index for exact lookup of long `TEXT` values.
The index accelerates searches; it does not prevent duplicates. All application
writers must follow the DB-service lock protocol. Direct SQL that bypasses it
can create duplicate destinations. A collision in the lock key only serializes
unrelated URLs; lookup still compares the complete destination.

**Chosen approach: support long destination URLs.** Recommendation links can carry
many query parameters, so a shortener should not reject them solely to fit a
database index. Long URLs also have established uses: Google's Maps Static API
supports up to 16,384 characters and mentions complex maps with many markers and
paths. [Google Maps Static API documentation](https://developers.google.com/maps/documentation/maps-static/start).

`UNIQUE(target_url)` with conflict recovery would make creation simpler and remove
the advisory lock. To reliably fit its B-tree index, we could set an application
limit such as 2,048 UTF-8 bytes (roughly 2,000 ASCII characters). This would exclude
some long destinations. That number is an alternative product limit, not
PostgreSQL's exact index limit or a general URL standard. This app instead accepts
up to 16,384 bytes after URL encoding.

The current hash-index and advisory-lock approach retains support for long values
at the cost of PostgreSQL-specific locking and a shared protocol for all writers.
It removes the B-tree indexed-value size restriction from destination lookup,
while the API's encoded-size validation protects redirect generation. PostgreSQL documents the
[B-tree size limit](https://www.postgresql.org/docs/16/btree-intro.html) and the
[lack of uniqueness checks in hash indexes](https://www.postgresql.org/docs/16/hash-intro.html).

### URL size and redirect headers

The 16 KiB encoded-destination limit is paired with
`server.tomcat.max-http-response-header-size: 32KB`. This leaves room for the
`Location` value and the other response headers, avoiding a successful creation
followed by a redirect that exceeds Tomcat's header buffer. The request-header
budget is unchanged: creation submits the destination inside the JSON body.

### Transaction boundaries and retries

The coordinator uses `Propagation.NEVER` to reject an enclosing transaction.
The DB service uses `REQUIRED` (the default) with `READ_COMMITTED`, so each call
from the coordinator starts its own transaction. `saveAndFlush` exposes insert
constraint failures inside the DB service. Its Spring proxy completes rollback
before the coordinator retries; a successful result reaches the controller only
after commit. The two services make this an external proxy call rather than
transactional self-invocation.

Only SQLSTATE `23505` on `uk_short_links_code` is translated into a retryable code
collision. Other database failures propagate normally. Ten failed code attempts
return `503 Service Unavailable`, allowing a later retry.

### Schema and frontend

- Flyway owns the schema through `V1__init.sql`. Initialization uses
  `CREATE TABLE IF NOT EXISTS` and `CREATE INDEX IF NOT EXISTS`, with comments on
  the table, columns, and URL index. Hibernate uses `ddl-auto: validate`.
- Open Session in View is disabled to keep persistence work inside service
  transactions; controllers serialize response DTOs.
- Plain HTML, CSS, and JavaScript live in `resources/static`; there is no frontend
  build step or external asset dependency. `defer` executes the script after HTML
  parsing; `addEventListener` binds form and button actions.
- The form calls the same-origin API, blocks duplicate submissions while waiting,
  and retains the entered URL on errors. API values are displayed as text.
  Its ten-second timeout does not guarantee that the server rolled back; retrying
  the same destination reuses the saved mapping.
- Static resources use `no-store` with Last-Modified checks disabled, so rebuilt
  local JARs do not reuse stale assets with fixed archive timestamps.
- Only the Actuator health endpoint is exposed, without component details.
  PostgreSQL and the demo application ports are bound to localhost.

## Scope and limits

- Base62 provides `62^4 = 14,776,336` codes. Four-character codes can be enumerated
  and are not access-control tokens.
- Exhausting ten retries does not prove that the entire code space is occupied.
- Destinations are limited to 16,384 bytes after encoding, including query and
  fragment. External HTTP clients, proxies, and destination servers can impose
  their own limits.
- The two-instance demo verifies shared-data correctness. It does not provide
  database high availability or constitute a load benchmark.
