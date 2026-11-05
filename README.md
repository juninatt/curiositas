# Curiositas

Curiositas turns scattered and contradictory historical facts into a curated, versioned dataset
with honest uncertainty, and publishes it through a standard API that any client can read once or
keep in sync with. It starts with historical people, from antiquity to modern times.

Raw facts are easy to find but hard to use. Sources disagree (Julius Caesar was born on 12 or
13 July 100 BC), give only approximate years, or mark a birthplace as merely probable. Curiositas
adds value in three steps:

| Step | What it means |
|---|---|
| **Curate** | Choose which people to include and settle conflicting sources. Facts are fetched from Wikidata rather than typed in by hand, and every change is reviewed before it is stored. |
| **Model** | Store only what the sources support: a date can be known to the year or month only, with an uncertainty in years, and derived values such as age are calculated as ranges. |
| **Publish** | Serve the data through a contract-first REST API, designed for clients that load everything once or keep in sync with every change, including deletions. |

```
Wikidata ──fetch──▶ JSON files in git ──import──▶ PostgreSQL ──REST API──▶ clients
                    (reviewed as diffs)
```

User interfaces are separate projects that only use the API, such as
[Conspectus](https://github.com/juninatt/Conspectus). The project also serves as a showcase of
API design and API testing.

**Status:** modelling, the read and write API, the revision history, synchronisation and fetching
persons from Wikidata are in place. The first theme, the Roman Empire, is next; see the
[backlog](docs/BACKLOG.md).

## Tech stack

- Java 21, Spring Boot 4.1, Spring Data JPA, Spring Security
- PostgreSQL 18 with Flyway migrations
- Contract-first REST API (OpenAPI), errors as RFC 9457 Problem Details, Swagger UI
- Tests: JUnit 5, AssertJ, Testcontainers, and REST Assured with every request and response
  validated against the OpenAPI contract

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

The unit and component tests (`*Test`) run first. The API tests (`*IT`) run after them: they
start the whole application and call it over real HTTP, like any client, and fail if a request
or response differs from the contract.

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
| `GET /api/v1/persons/{id}/revisions` | Every change to a person, oldest first, also after it is deleted | No |
| `GET /api/v1/snapshot` | Every person at once, with the version of the dataset, for clients that keep a copy | No |
| `GET /api/v1/changes?after=57&limit=100` | Every change after a version, deletions included, to keep such a copy in sync | No |

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

Every read that can be cached has an `ETag`: a person its version, a page of persons and the
snapshot the version of the dataset and of the application. Sending it back in `If-None-Match`
gives `304 Not Modified` without a body until something changes.

| Status | When |
|---|---|
| 304 | Nothing has changed since the response with the `ETag` in `If-None-Match` |
| 400 | The request cannot be read, for example malformed JSON or an invalid parameter |
| 401 | A write without valid credentials |
| 404 | No person with that id has ever existed |
| 406 | The `Accept` header allows neither `application/json` nor `application/problem+json` |
| 409 | The person already exists: same Wikidata id, or the same name, birth, death and birth country |
| 410 | The person has been deleted; its history is still at `/persons/{id}/revisions` |
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

Every change, whether made through the API or by an import, is also recorded in the database as
a revision holding the whole person, so the application keeps the full history of the data,
including deleted persons.

The application refuses to start when the database and the files differ, and lists every
difference by file and field. It also refuses when a person has no history, which happens to
persons stored before the history existed. The data commands fix both, and work even when the
application does not start:

| Command | What it does |
|---|---|
| `./data.sh check` | Lists the differences without changing anything |
| `./data.sh import` | Makes the database match the files: creates, updates and deletes persons, and records the first revision of persons without a history. The usual fix, for example after `git pull`. |
| `./data.sh export` | Writes the database to the files, for the rare case where the database is right. Review the result with `git diff`. |
| `./data.sh fetch` | Writes the persons of every theme from Wikidata to the files, without touching the database (see [Themes](#themes)). |

On Windows, use `.\data.cmd` instead of `./data.sh`. In IntelliJ IDEA, the same commands are shared
run configurations under **Data** (from the [`.run`](.run) folder). An import checks every file
first and changes nothing if any file is invalid, listing each problem with its file and field, or
with line and column for broken JSON. A file with an unknown field or broken JSON is reported with
that problem first; its other problems show once it is fixed. Persons keep their ids across imports
as long as they are recognisably the same person, by Wikidata id or else by name and life dates.

### Themes

Persons are curated by theme: a time and a place, such as the Roman Empire. Each theme is a file
in [`data/themes/`](data/themes) that lists the persons chosen for it by their Wikidata ids; the
names are there only to make the list readable.

```json
{
  "name": "The Roman Empire",
  "persons": [
    { "wikidataId": "Q1048", "name": "Julius Caesar" }
  ]
}
```

`./data.sh fetch` reads every person in the themes from Wikidata and writes their files, so the
choice of persons is made by hand and the facts come from Wikidata. Nothing reaches the database
until the result has been reviewed with `git diff` and imported:

```
choose persons ──▶ data/themes ──fetch──▶ data/persons ──review──▶ git commit ──import──▶ database
```

What a fetch takes from Wikidata, and how:

| Fact | Rule |
|---|---|
| Dates | Never more precise than Wikidata: a date known to the month gets no day, and a century becomes a year with an uncertainty of 50. "Circa" gives up the finest known part, so circa 8 August becomes August and circa 1697 becomes 1697 ± 5. |
| Disagreeing sources | Wikidata's preferred statement is used; without one, the statements are combined into what they share, so 12 or 13 July becomes July. The fetch lists every person whose sources disagree. |
| Places | The English name, after how sure the source is, such as "possibly Kinsale". |
| Birth country | The modern country of the place of birth: a country without an end date that has an ISO code, so Rome gives Italy, not the Roman Republic. |
| Other names | Only names Wikidata gives a type: birth name, nickname and pseudonym. |

A fetch replaces the files of the persons in the themes, so corrections belong in Wikidata, where
they help everyone. Files of other persons are left alone. If any person breaks the rules of the
API, for example with a death before the birth, nothing is written.

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
