# Backlog

Curiositas curates historical facts, models them with honest uncertainty and publishes them as a
versioned dataset through a REST API (see [README](../README.md)). The roadmap follows those three
steps. Tests are written alongside every step, never afterwards.

## Done

- [x] Project skeleton: Maven, Spring Boot, PostgreSQL via Docker Compose, Flyway, CI, release-please
- [x] `HistoricalDate` value type with display text and age range, with thorough unit tests
- [x] OpenAPI contract with generated interfaces and models
- [x] Person: create, read, replace and delete, with validation, Problem Details, authentication for
  writes, ETag with If-Match, and duplicate detection
- [x] Swagger UI and a read-only preview page
- [x] JSON files in `data/` as the source of truth, one file per person: export, import, a
  round-trip test, writing the files with every change, and refusing to start when the database
  and the files differ

## Roadmap

### 1. Prove the API against its contract

- [x] Run integration tests separately with failsafe, reported in CI
- [x] Test the person lifecycle over real HTTP with REST Assured and a real database
- [x] Validate every request and response in those tests against the OpenAPI contract
- [x] Cover every error response end to end (400, 401, 404, 406, 409, 412, 422, 428)

### 2. Revision history owned by the application

- [ ] Record every change (created, updated, deleted) in the same transaction as the change, with
  its source (API, import, Wikidata), time and a full snapshot of the person
- [ ] Show a person's revisions, including those of deleted persons
- [ ] Answer 410 Gone for a deleted person, instead of 404 for one that never existed

### 3. Publish and synchronise

- [ ] Bulk export of all persons in one response, for clients that load everything once
- [ ] ETag and 304 Not Modified, so unchanged data is never sent again
- [ ] Change feed by sequence number (`/changes?after=…`), including deletions, so clients can keep
  in sync even after missing several syncs
- [ ] The highest sequence number as the dataset version
- [ ] Prove that replaying the change feed from the start gives exactly the current data, and that
  a client syncing at random intervals always ends up identical to the server

### 4. Curate from Wikidata

- [ ] Fetch persons for one theme (for example the Golden Age of Piracy) from a list of Wikidata ids
- [ ] Turn Wikidata's statements, ranks and precisions into honest dates and places, and show
  conflicting sources instead of choosing silently
- [ ] Write the result to `data/` for review as a git diff before it is imported

### 5. More for clients

- [ ] CORS for browser clients such as Conspectus
- [ ] Listing with filters and sorting
- [ ] One statistics endpoint
- [ ] Load Mockito as a Java agent in the test build; it currently attaches itself at runtime,
  which future JDK versions will block

## Before version 1.0

Version 1.0 promises that the API will not change in ways that break existing clients. Adding
optional fields, endpoints and query parameters is safe afterwards; making a field required,
renaming, removing or changing the meaning of one is not. Before 1.0:

- [x] JSON backup in `data/`, so no data depends on a single database
- [x] The API is proven to match its contract (roadmap step 1)
- [ ] The revision history and the change feed have the shape they are meant to keep (steps 2–3)
- [ ] The person fields have the shape they are meant to keep

## After version 1.0

- [ ] Back up the revision history as append-only files, so it survives the loss of the database

## Person fields

### In the first version

- `id`, `wikidataId` (optional, unique)
- `name`, `alsoKnownAs` (typed: nickname, pen name, regnal name, birth name, spelling variant)
- `birth`, `death`, `floruit` (all `HistoricalDate`: year, optional month and day, uncertainty in
  years)
- `birthPlace`, `deathPlace` (free text until places exist)
- `birthCountry` (ISO 3166-1 alpha-2 code, shown as the full country name), `gender`
- `version`, `createdAt`, `updatedAt`

### Added later

All optional, so they can be added without breaking existing clients.

- [ ] `sources` (URL, title, date accessed)
- [ ] `summary`, `whyInteresting`
- [ ] `historicity` (historical, probable, legendary), `HISTORICAL` when not given
- [ ] `causeOfDeath` (fixed list plus free text detail)
- [ ] Tags

## More of history

- [ ] Polities (historical states and realms, optionally mapped to a modern country)
- [ ] Nationalities
- [ ] Residence periods (where a person lived, and when)
- [ ] Titles (ranks and offices) and occupations, each with an optional period
- [ ] Native languages (ISO 639-3, includes historical languages)
- [ ] Religion as periods, since it can change during a life
- [ ] Social origin and peak social status
- [ ] Events (title, start, end, type, sources)
- [ ] Participation: person and event, with type (participated, caused, influenced, affected by)
- [ ] Relations between people (teacher, rival, spouse, killed by, ...)
- [ ] Places with coordinates, in a hierarchy (Bristol, England, United Kingdom, Europe), so an
  uncertain birthplace can be given at the level the sources support
- [ ] Which polity ruled a place over time, so a birthplace can be shown both as its modern country
  and as the realm it belonged to then (Königsberg: Prussia in 1724, Russia today)

## Later

- [ ] Remove the preview page once Conspectus shows the data
- [ ] Public deployment with HTTPS, building its database from `data/` on every deployment
- [ ] Bulk import of CSV with per-row error reporting
- [ ] Black-box API test suite (Postman/Newman or Bruno)
- [ ] Allure reports, mutation testing (PIT), load testing (Gatling)

## TODO

- Family and private life, for example children who died young. Probably modelled as relations
  to lightweight person records rather than as fields on the person.
