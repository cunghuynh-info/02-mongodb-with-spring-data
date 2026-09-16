# MongoDB with Spring Data

A practice project for working through MongoDB data modelling, querying, indexing, paging,
bulk writes, transactions, change streams and Atlas Search from Spring Data.

The task breakdown lives in [docs/PLAN.md](docs/PLAN.md).

## The lab

`compose.yaml` runs `mongodb/mongodb-atlas-local:8.0` — a single-node replica set bundled
with `mongot`. That one image covers three things plain `mongo` cannot: multi-document
transactions and change streams need the replica set, and `$search` needs mongot.

```bash
docker compose up -d                  # mongodb + one-shot sample-data seed
docker compose --profile tools up -d  # also starts mongo-express on http://localhost:8081
docker compose logs -f mongo-seed     # watch the restore (first run downloads ~380 MB)
```

The `mongo-seed` container downloads the Atlas sample dataset, restores the databases listed
in `SAMPLE_DATABASES`, and creates the Atlas Search indexes from
`docker/seed/search-indexes.js`. It exits 0 when finished. The archive is cached in a volume,
and the restore is skipped on later runs (a marker document in `lab_meta.seed_state`), so
`docker compose up` stays cheap.

First run takes a few minutes: image pull, ~380 MB download, then the restore.

Defaults come from `.env.example` — copy it to `.env` to change the port, credentials, or
which sample databases get restored:

```bash
cp .env.example .env
```

Restore again after changing `SAMPLE_DATABASES`:

```bash
SEED_FORCE=true docker compose up mongo-seed
```

Stop, keeping the data:

```bash
docker compose down
```

Wipe everything including the cached archive:

```bash
docker compose down -v
```

## Connecting

```
mongodb://root:example@localhost:27017/sample_mflix?authSource=admin&directConnection=true
```

`directConnection=true` matters: without it the driver reads the replica-set config and tries
to reach the member under its container-internal hostname, which does not resolve from the host.

Check the data:

```bash
docker compose exec mongodb mongosh -u root -p example --authenticationDatabase admin \
  --eval 'db.getSiblingDB("sample_mflix").movies.countDocuments()'
```

## Running the app

```bash
./mvnw spring-boot:run
```

The URI in `src/main/resources/application.yaml` matches the compose defaults and can be
overridden with the `MONGO_URI` environment variable.

## What is implemented

All nine phases of [docs/PLAN.md](docs/PLAN.md), with the measurements in
[docs/notes/](docs/notes/). Every endpoint is in [http/requests.http](http/requests.http),
ready to run in the IntelliJ HTTP client.

| Phase | Notes | Headline finding |
|---|---|---|
| 1 embedding / referencing | [01](docs/notes/01-modelling.md) | `$lookup` 33 ms vs eager `@DocumentReference` 80 ms for the same 10 comments |
| 2 Criteria + aggregation | [02](docs/notes/02-querying.md) | `spring.data.mongodb.uri` is deprecated in Boot 4 and fails silently |
| 3 indexes | [03](docs/notes/03-indexes.md) | covered query: 2,457 rows, **0 documents examined** |
| 4 paging | [04](docs/notes/04-paging.md) | deep offset examines **10,020** index keys; keyset examines **20** |
| 5 bulk write | [05](docs/notes/05-bulk-write.md) | 20,000 docs: 60,298 ms one-by-one vs 564 ms batched (**107x**) |
| 6 transactions | [06](docs/notes/06-transactions.md) | rollback verified; 20 concurrent transfers keep the total invariant |
| 7 change streams | [07](docs/notes/07-change-streams.md) | a rolled-back transaction emits no events at all |
| 8 Atlas Search | [08](docs/notes/08-atlas-search.md) | fuzzy edit distance is measured against the *stemmed* term |

### Endpoints

**Phase 1 - embedding and referencing**

| Method | Path | |
|---|---|---|
| GET | `/api/movies/{id}` | 1.1 embedded document |
| GET | `/api/movies/{id}/comments` | 1.2 referenced, paged |
| GET | `/api/movies/{id}/comments/manual` | 1.2 two explicit queries |
| GET | `/api/movies/{id}/comments/via-reference` | 1.3 `@DocumentReference`, lazy |
| GET | `/api/movies/{id}/comments/via-eager-reference` | 1.3 the N+1 |
| GET | `/api/movies/{id}/comments/via-lookup` | 1.4 one round trip |
| POST | `/api/movies/{id}/comments` | 1.5 insert + bounded embed |

**Phase 2 - Criteria, MongoTemplate, aggregation**

| Method | Path | |
|---|---|---|
| GET | `/api/movies` | 2.2 dynamic `Criteria`, offset paged |
| GET | `/api/movies/summaries` | 2.3 interface projection |
| POST | `/api/movies/{id}/votes` | 2.4 `$inc` via `findAndModify` |
| POST/DELETE | `/api/movies/{id}/genres` | 2.4 `$addToSet` / `$pull` |
| GET | `/api/stats/genres` | 2.5 unwind + group |
| GET | `/api/stats/browse` | 2.6 `$facet` + `$bucket` |
| GET | `/api/stats/most-commented` | 2.7 `$lookup` with sub-pipeline |
| GET | `/api/stats/sales-revenue` | 2.8 second database |
| GET | `/api/stats/sales-revenue-dense` | 2.8 `$dateTrunc` + `$densify` |
| GET | `/api/movies/explain` | 2.9 explain (`?raw=true` for everything) |

**Phase 3 - indexes**

| Method | Path | |
|---|---|---|
| GET | `/api/indexes/{collection}` | 3.2 what exists, with sizes |
| GET | `/api/indexes/covered-query` | 3.4 `docsExamined: 0` |
| GET | `/api/indexes/partial-index` | 3.6 implies the filter vs does not |

**Phase 4 - paging**

| Method | Path | |
|---|---|---|
| GET | `/api/movies/slice` | 4.3 no count query |
| GET | `/api/movies/scroll` | 4.4 Spring Data `Window` |
| GET | `/api/movies/keyset` | 4.5 / 4.6 hand-written, opaque cursor |
| GET | `/api/movies/paging-benchmark` | 4.2 offset vs keyset at depth |
| GET | `/api/movies/keyset/explain` | 4.7 |

**Phases 5 and 6 - bulk write and transactions**

| Method | Path | |
|---|---|---|
| GET | `/api/analytics/bulk/benchmark` | 5.3 one-by-one vs batched |
| GET | `/api/analytics/bulk/duplicate-key` | 5.2 ordered vs unordered |
| POST | `/api/analytics/bulk/raise-limits` | 5.1 mixed batch |
| POST | `/api/analytics/bulk/upsert` | 5.5 idempotent upsert |
| GET | `/api/analytics/bulk/stream-to-bulk` | 5.6 streaming read, batched write |
| POST | `/api/analytics/transfer` | 6.2 with retry on transient labels |
| POST | `/api/analytics/transfer/rollback-demo` | 6.2 always 500, nothing written |
| GET | `/api/analytics/balances` `/transfers` | the invariant and the audit trail |

**Phase 7 - change data capture**

| Method | Path | |
|---|---|---|
| POST | `/api/cdc/start` `/stop` | 7.1 |
| GET | `/api/cdc/events` `/status` | 7.1 / 7.5 |
| POST | `/api/cdc/enable-pre-images` | 7.4 |
| DELETE | `/api/cdc/resume-token` | 7.5 |

**Phase 8 - Atlas Search**

| Method | Path | |
|---|---|---|
| GET | `/api/search/movies` | 8.1 / 8.2 text, score, highlights |
| GET | `/api/search/autocomplete` | 8.3 |
| GET | `/api/search/compound` | 8.4 |
| GET | `/api/search/facets` | 8.5 `$searchMeta` |
| GET | `/api/search/page` | 8.6 `searchAfter` |
| GET | `/api/search/fuzzy` | 8.7 |
| GET/POST | `/api/search/index` | status / rebuild |

Filters shared by `/api/movies*` and `/api/stats/browse`: `title`, `genres` (repeatable),
`yearFrom`, `yearTo`, `minRating`, `castMember`, plus `page`/`size`/`sort`.

```bash
curl 'http://localhost:8080/api/movies?genres=Crime&yearFrom=1970&yearTo=1980&minRating=8&size=3&sort=year,asc'
curl 'http://localhost:8080/api/movies/paging-benchmark?yearFrom=1900&size=20&deepPage=500'
curl 'http://localhost:8080/api/search/movies?q=gangster&limit=5'
```

## Tests

```bash
./mvnw test      # 9 unit tests - no Docker needed
./mvnw verify    # + 62 integration tests against a Testcontainers deployment
```

The integration tests start their own `mongodb/mongodb-atlas-local` container and seed small
fixtures, so they neither need nor touch the compose lab. The Atlas Search tests are the slow
ones: mongot has to build the index before anything can be queried.

## Startup behaviour

Two `ApplicationRunner`s run against whatever the app connects to:

- `LabStartupCheck` warns when a collection is empty, which is what catches a misconfigured
  connection - the failure is otherwise completely silent.
- `IndexConfig` creates every index the lab uses, idempotently.

Disable either with `lab.startup-check.enabled=false` / `lab.index-creation.enabled=false`.
