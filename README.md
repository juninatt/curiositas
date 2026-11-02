# Curiositas

Curiositas turns scattered and contradictory historical facts into a curated, versioned dataset
with honest uncertainty, and publishes it through a standard API that any client can read once or
keep in sync with. It starts with historical people, from antiquity to modern times.

Raw facts are easy to find but hard to use. Sources disagree (Julius Caesar was born on 12 or
13 July 100 BC), give only approximate years, or mark a birthplace as merely probable. Curiositas
adds value in three steps:

| Step | What it means |
|---|---|
| **Curate** | Choose which people to include and settle conflicting sources. Facts are to be fetched from Wikidata rather than typed in by hand, and every change is reviewed before it is stored. |
| **Model** | Store only what the sources support: a date can be known to the year or month only, with an uncertainty in years, and derived values such as age are calculated as ranges. |
| **Publish** | Serve the data through a contract-first REST API, designed for clients that load everything once or keep in sync with every change, including deletions. |

```
Wikidata ──fetch──▶ JSON files in git ──import──▶ PostgreSQL ──REST API──▶ clients
                    (reviewed as diffs)
```

User interfaces are separate projects that only use the API, such as
[Conspectus](https://github.com/juninatt/Conspectus). The project also serves as a showcase of
API design and API testing.

**Status:** curating by hand, modelling and the read and write API are in place. Fetching from
Wikidata, the revision history and synchronisation are planned; see the
[backlog](docs/BACKLOG.md).

## Tech stack

- Java 21, Spring Boot 4.1, Spring Data JPA, Spring Security
- PostgreSQL 18 with Flyway migrations
- Contract-first REST API (OpenAPI), errors as RFC 9457 Problem Details, Swagger UI
- Tests: JUnit 5, AssertJ, Testcontainers
- Planned: REST Assured, contract validation against the OpenAPI spec

## Getting started

Requirements: JDK 21 and Docker (Docker Desktop on Windows/macOS). Docker must be running both
when starting the application and when running the tests.

Anyone can read data, but writing requires the admin account (username `admin`). The application
refuses to start without an admin password, which is given through an environment variable:

```bash
export CURIOSITAS_ADMIN_PASSWORD=choose-a-password
```

On Windows in PowerShell: `$env:CURIOSITAS_ADMIN_PASSWORD = "choose-a-password"`.

```bash
./mvnw spring-boot:run
```

The application starts the PostgreSQL container in `compose.yaml` automatically, runs with the
`dev` profile and first checks that the database matches the files in `data/` (see
[Data](#data)). When starting it from an IDE instead, set the active profile to `dev`. Then open:

| URL | What it shows |
|---|---|
| http://localhost:8080 | A read-only table of the persons in the database (development preview) |
| http://localhost:8080/swagger-ui.html | Swagger UI, for reading the API and trying every endpoint |
| http://localhost:8080/openapi.yaml | The OpenAPI contract |

Run all tests (Docker must be running, the tests use a real PostgreSQL container):

```bash
./mvnw verify
```

On Windows in PowerShell, use `.\mvnw.cmd` instead of `./mvnw`, for example
`.\mvnw.cmd spring-boot:run`.

## The API

The contract in [`src/main/resources/openapi.yaml`](src/main/resources/openapi.yaml) is written
first; the server's interfaces and models are generated from it on every build, so the code cannot
drift from the documentation.

| Method and path | Purpose | Login |
|---|---|---|
| `GET /api/v1/persons?page=0&size=20` | List persons, ordered by name | No |
| `GET /api/v1/persons/{id}` | Get one person, with its version as `ETag` | No |
| `POST /api/v1/persons` | Create a person | Yes |
| `PUT /api/v1/persons/{id}` | Replace a person; requires `If-Match` with the latest `ETag` | Yes |
| `DELETE /api/v1/persons/{id}` | Delete a person | Yes |

Creating a person:

```bash
curl -u admin:choose-a-password -X POST http://localhost:8080/api/v1/persons \
  -H "Content-Type: application/json" \
  -d '{"name": "Edward Teach", "alsoKnownAs": [{"name": "Blackbeard", "type": "NICKNAME"}],
       "birth": {"year": 1680, "uncertaintyYears": 5}, "death": {"year": 1718, "month": 11, "day": 22},
       "gender": "MALE"}'
```

The response adds derived values that are calculated on every request and never stored, such as
`"display": "1680 ± 5"` for the birth and `"ageAtDeath": {"min": 32, "max": 43}`.

| Status | When |
|---|---|
| 400 | The request cannot be read, for example malformed JSON or an invalid parameter |
| 401 | A write without valid credentials |
| 404 | No person with that id |
| 406 | The `Accept` header allows neither `application/json` nor `application/problem+json` |
| 409 | The person already exists: same Wikidata id, or the same name, birth, death and birth country |
| 412 | The person was changed since the `ETag` in `If-Match` was read |
| 422 | Readable but invalid data, such as 31 April or a death before the birth, with one error per field |
| 428 | `If-Match` is missing when replacing a person |

## Data

The JSON files in [`data/persons/`](data/persons) are the source of truth, one file per person,
in the same format as the request that creates a person. The database is a working copy that
mirrors them, so data never depends on a single database and every change shows up in git.

```json
{
  "name": "Edward Teach",
  "wikidataId": "Q213518",
  "alsoKnownAs": [{ "name": "Blackbeard", "type": "NICKNAME" }],
  "birth": { "year": 1680, "uncertaintyYears": 5 },
  "death": { "year": 1718, "month": 11, "day": 22 },
  "gender": "MALE"
}
```

When running locally, every change made through the API is written to its file before it is
committed to the database; if the file cannot be written, the change is rolled back. Committing
`data/` makes the change permanent.

The application refuses to start when the database and the files differ, and lists every
difference by file and field. The data commands fix that, and work even when the application
does not start:

| Command | What it does |
|---|---|
| `./data.sh check` | Lists the differences without changing anything |
| `./data.sh import` | Makes the database match the files: creates, updates and deletes persons. The usual fix, for example after `git pull`. |
| `./data.sh export` | Writes the database to the files, for the rare case where the database is right. Review the result with `git diff`. |

On Windows, use `.\data.cmd` instead of `./data.sh`. An import checks every file first and
changes nothing if any file is invalid, listing each problem with its file and field, or with
line and column for broken JSON. A file with an unknown field or broken JSON is reported with
that problem first; its other problems show once it is fixed. Persons keep their ids across
imports as long as they are recognisably the same person, by Wikidata id or else by name and life
dates.

## Design principles

- The schema grows step by step through Flyway migrations.
- Derived data such as age is never stored; it is calculated from stored dates.
- Records carry the sources they are based on (sources for persons are the next planned fields).
- Unknown stays unknown: missing values are left empty, never guessed.
- Fixed fields for anything that is counted or grouped on; tags for everything else.

### Uncertain historical dates

Exact dates are often unknown for historical people. A date is stored as a year with an optional
month and day, so it is never more precise than its source. An uncertainty in years expresses
approximate dates, for example 1680 ± 5. Ages are calculated as a range.

Years use astronomical numbering, where year 0 is 1 BC and year -43 is 44 BC.

## Known limitations

- Dates are stored as the source gives them. Dates before 1582 are usually in the Julian calendar
  and are not converted, which may shift them by up to about ten days.
- HTTP Basic authentication sends the password with every request, so a public deployment must
  use HTTPS.

## Development workflow

- Work is done directly on `main`, one feature at a time, in small commits that each build.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/) with a scope,
  for example `feat(person): add person listing`.
- Versions and the changelog are managed by
  [release-please](https://github.com/googleapis/release-please). It keeps a release pull request
  open; merging it creates a tag and a GitHub release.

See [docs/BACKLOG.md](docs/BACKLOG.md) for the roadmap.
