# MongoDB + Spring Data practice plan

Goal: work through the eight topics below as small, runnable slices on top of the
Atlas sample dataset, each one ending in a REST endpoint plus a test that proves
the behaviour (not just that the code compiles).

Stack already in place: Spring Boot 4.1.1 / Java 17, `spring-boot-starter-data-mongodb`,
Lombok, and the `compose.yaml` lab (single-node replica set + mongot + seeded sample data).

## Dataset to topic map

| Topic | Database / collection | Why this one |
|---|---|---|
| Embedding vs referencing | `sample_mflix` - `movies`, `comments`, `users` | comments are a classic unbounded child set; awards/imdb are natural embeds |
| Criteria + MongoTemplate | `sample_mflix.movies` | wide, messy documents; lots of optional filters |
| Aggregation | `sample_mflix.movies`, `sample_supplies.sales` | facets, unwind, bucket, lookup |
| Compound + partial indexes | `sample_training.inspections`, `sample_mflix.movies` | selective and sparse-ish fields worth a partial index |
| Paging | `sample_mflix.movies` (~21k docs) | deep offsets hurt visibly at this size |
| Bulk write | `sample_analytics.accounts`, `sample_training.grades` | natural batch updates |
| Transactions | `sample_analytics` - `accounts`, `transactions`, `customers` | money transfer across two collections |
| Change data capture | `sample_analytics.accounts`, `sample_mflix.comments` | write traffic to observe |
| Atlas Search | `sample_mflix.movies` (index `movies_search`) | text, autocomplete, facets, highlighting |

## Target package layout

```
vn.infodation.mongodb
├── config/        MongoConfig, IndexConfig, TransactionConfig, MongoTemplates (per-database)
├── common/        PageResponse, KeysetPage, CursorCodec, GlobalExceptionHandler
├── mflix/         domain, repository (+ custom fragment), service, web
├── analytics/     domain, service (transfer, bulk), web
├── training/      domain, index + explain playground
├── cdc/           change-stream listeners, resume-token store
└── search/        Atlas Search service + DTOs
```

---

> **Status:** all phases implemented and verified against the lab. Findings and measurements
> are in `docs/notes/`, one file per phase; every endpoint is in `http/requests.http`.
> Two items are deliberately left open and marked below: the reactive SSE change-stream variant
> (7.2, optional) and Micrometer instrumentation (9.4, optional).

## Phase 0 - foundation

- [x] **0.1 Start the lab.** `docker compose up -d`, wait for `mongo-lab-seed` to exit 0.
      Verify: `docker compose exec mongodb mongosh -u root -p example --authenticationDatabase admin --eval 'db.getSiblingDB("sample_mflix").movies.countDocuments()'`
      returns roughly 21k.
- [x] **0.2 Prove the app connects.** Built as `LabStartupCheck`, an `ApplicationRunner` that
      logs a count per database and warns when a collection is empty. Kept rather than deleted:
      a misconfigured connection is silent otherwise (see `docs/notes/02-querying.md`).
      Disable with `lab.startup-check.enabled=false`.
- [x] **0.3 Multi-database access.** The sample data spans several databases but Spring Boot
      wires one. Add a `MongoTemplates` config exposing `mflixTemplate`, `analyticsTemplate`,
      `trainingTemplate` as `new MongoTemplate(mongoClient, "<db>")`, injected by qualifier.
      Keep the auto-configured template as the `sample_mflix` default.
- [x] **0.4 Test harness.** `spring-boot-testcontainers` plus `testcontainers-mongodb` and
      `testcontainers-junit-jupiter` (Testcontainers 2.x renamed those artifacts). Base class
      `AbstractMongoIntegrationTest` wires `MongoDBAtlasLocalContainer` through
      `@DynamicPropertySource` rather than `@ServiceConnection`, which has no factory for it.
      One static container for the whole suite. `*IT` classes run under failsafe (`mvn verify`),
      so `mvn test` stays Docker-free.
- [x] **0.5 Conventions, decided once.** `@Document(collection = "...")` always explicit;
      no `@Indexed` (indexes live in `IndexConfig`); `Instant` for dates; money as
      `BigDecimal` with `@Field(targetType = FieldType.DECIMAL128)`.

## Phase 1 - embedding and referencing

- [x] **1.1 Embedded model.** `Movie` with embedded `ImdbRating`, `Awards`, `Tomatoes` value
      objects and `List<String>` genres/cast/directors. Read endpoint `GET /movies/{id}`.
- [x] **1.2 Referenced model, by hand.** `Comment` holding `movieId` (ObjectId) plus a
      denormalised `name`/`email`. `GET /movies/{id}/comments` resolves with a second query -
      the deliberately explicit version.
- [x] **1.3 `@DocumentReference`.** `Movie.comments` is an inverse lazy reference
      (`lookup = "{ 'movie_id' : ?#{#self._id} }"`, `@ReadOnlyProperty`); the eager side is a
      separate `CommentRef` class, because two properties cannot map to the same `movie_id`
      field. Measured in `docs/notes/01-modelling.md`: the eager inverse is the slowest of the
      four paths.
- [x] **1.4 `$lookup` version.** Same payload in one round trip via aggregation. Three routes
      to the same JSON is the point of the exercise.
- [x] **1.5 Bounded embed.** `Movie.recentComments` - last 5 comments embedded, maintained with
      `$push` + `$slice: -5`. The extended-reference pattern; note the extra write cost.
- [x] **1.6 Write-up.** `docs/notes/01-modelling.md`: when embedding wins (1:few, read together,
      bounded) and when referencing wins (1:many unbounded, written independently, 16 MB ceiling).

**Done when:** the three endpoints return equivalent JSON and a test asserts the
document-count difference between the embedded and referenced shapes.

## Phase 2 - Criteria, MongoTemplate, aggregation

- [x] **2.1 Custom repository fragment.** `MovieRepositoryCustom` + `MovieRepositoryImpl`
      injecting `MongoTemplate`; `MovieRepository extends MongoRepository<Movie, ObjectId>, MovieRepositoryCustom`.
- [x] **2.2 Dynamic filter.** `MovieSearchCriteria` (title contains, genres in, year range,
      min imdb rating, cast member). Build `Criteria` conditionally so only non-null fields
      contribute. Cover `andOperator`/`orOperator`, anchored `regex`, and `elemMatch`.
      - note: `elemMatch` ended up in `SalesQueryService`, not on `cast` - see
        `docs/notes/02-querying.md` for why it cannot work on an array of scalars here.
- [x] **2.3 Projections.** `mongoTemplate.query(Movie.class).as(MovieSummary.class)` with a
      closed interface projection, including a nested `ImdbSummary`. Spring derives the field
      mask from the getters.
- [x] **2.4 Update operators.** `findAndModify` with `Update.inc/set/push/addToSet/pull`, plus
      upsert and `FindAndModifyOptions.returnNew(true)`.
- [x] **2.5 Aggregation A - genre stats.** `unwind(genres)` to `group(genres)` with count,
      average rating, max year, then `sort` and `limit`. Typed output class.
- [x] **2.6 Aggregation B - faceted browse.** One `$facet` returning genre counts, decade
      buckets (`$bucket`), and the first page of results.
- [x] **2.7 Aggregation C - join.** Pipeline-form `$lookup` of comments into movies,
      `$addFields` comment count, `$match` on that count.
- [x] **2.8 Aggregation D - sales.** On `sample_supplies.sales`: revenue per store per month
      with `$unwind items` and `$multiply`, grouped by `$year`/`$month`.
      - [x] and the `$dateTrunc` + `$densify` form, at `GET /api/stats/sales-revenue-dense`.
- [x] **2.9 Options.** `AggregationOptions.builder().allowDiskUse(true).cursorBatchSize(...)`,
      and how to read `explain` output for a pipeline.

**Done when:** each pipeline has a test asserting a known value from the sample data
(e.g. the `Drama` genre count), so a wrong pipeline fails instead of silently returning `[]`.

## Phase 3 - compound and partial indexes

- [x] **3.1 Baseline.** Already recorded in `docs/notes/02-querying.md`: `SORT` over a
      `COLLSCAN`, 21,349 docs examined to return 20, 15 ms. `GET /api/movies/explain` and
      `MovieRepositoryIT.explainShowsACollectionScanBeforeAnyIndexExists` both cover it.
- [x] **3.2 `IndexConfig`.** An `ApplicationRunner` creating indexes through
      `IndexOperations.ensureIndex`, idempotent and logged. No `@Indexed` annotations.
- [x] **3.3 Compound index.** `{ genres: 1, year: -1, "imdb.rating": -1 }` on `movies`.
      Demonstrate ESR ordering (Equality, Sort, Range): re-run 3.1 and show `IXSCAN`, then show
      a query that breaks the prefix rule and falls back to a scan.
- [x] **3.4 Covered query.** A projection the index alone satisfies - `totalDocsExamined: 0`.
- [x] **3.5 Partial index.** On `sample_training.inspections` with
      `partialFilterExpression: { result: "Fail" }`, and on `movies` an index on
      `imdb.rating` limited to documents where it exists and exceeds 7. Compare
      `db.collection.stats().indexSizes` against the equivalent full index.
- [x] **3.6 The partial-index gotcha.** Show that a query without the partial predicate will
      not use the index even when it logically could. This is the part people get wrong.
- [x] **3.7 Unique partial index.** Unique on `email` only where `email` exists - the modern
      replacement for a sparse unique index.
- [x] **3.8 Bonus.** TTL index on a scratch collection; wildcard index over the mixed
      `sample_airbnb` amenity-style fields.

**Done when:** `docs/notes/03-indexes.md` has a before/after table of `docsExamined` vs
`nReturned` per query, and a test asserts the winning plan is an `IXSCAN`.

## Phase 4 - paging: offset and keyset

- [x] **4.1 Offset paging.** `Page<Movie> findAll(Pageable)` and the `MongoTemplate` equivalent
      (`query.with(pageable)` plus a `count`). Endpoint `GET /movies?page=&size=&sort=`.
- [x] **4.2 Measure the cliff.** Time page 1 against page 1000 at size 20 and record it.
      Explain both costs: `skip` walking discarded documents, and the extra count round trip.
- [x] **4.3 `Slice` / `ScrollPosition.offset()`.** Drop the count when the UI has no total.
- [x] **4.4 Keyset paging.** `ScrollPosition.keyset()` with `Window<Movie> scroll(...)`, sorted
      on `{ year: -1, _id: -1 }`. Without the `_id` tiebreaker the page boundary is ambiguous -
      demonstrate the duplicated/skipped row.
- [x] **4.5 Keyset by hand.** The same query built with `Criteria` so the predicate is visible:
      `(year < :y) OR (year = :y AND _id < :id)`.
- [x] **4.6 Opaque cursor.** `CursorCodec` encoding the keyset values as base64; `KeysetPage<T>`
      returns `items` plus `nextCursor`. Raw field values should never be the API contract.
- [x] **4.7 Index support.** Confirm the keyset query rides the compound index from 3.3 and
      examines exactly `size` documents at any depth.

**Done when:** a test walks the entire collection by cursor asserting no duplicates and no
gaps, and `docs/notes/04-paging.md` holds the page-1 vs page-1000 timings.

## Phase 5 - bulk write

- [x] **5.1 `BulkOperations` basics.** `mongoTemplate.bulkOps(BulkMode.UNORDERED, Account.class)`
      mixing `updateOne`, upsert, `replaceOne` and `remove`; then read `BulkWriteResult`
      (matched, modified, upserted, inserted).
- [x] **5.2 Ordered vs unordered.** Force a duplicate-key failure mid-batch: ordered stops at
      the failure, unordered continues. Catch `BulkOperationException` and inspect `getErrors()`.
- [x] **5.3 Batch size.** 100k synthetic updates chunked at 1k per batch vs one-by-one `save()`.
      Record the timings - this is the entire reason the API exists.
- [x] **5.4 `insert(Collection)` vs `saveAll`.** Note that `saveAll` over new entities can still
      round-trip per document; prefer `insert(List)` for pure inserts.
- [x] **5.5 Idempotent upsert.** `upsert` with `$setOnInsert` for the created-at field so
      replays are safe.
- [x] **5.6 Bulk from a stream.** Read `sample_training.grades` with `mongoTemplate.stream()`
      and write recomputed averages back in batches, without holding the collection in memory.

**Done when:** a test asserts the exact `BulkWriteResult` counts, and the
unordered-continues-after-error behaviour is asserted rather than just described.

## Phase 6 - transactions

- [x] **6.1 Enable.** A `MongoTransactionManager` bean over `MongoDatabaseFactory`, plus
      `@EnableTransactionManagement`. Requires a replica set - the lab already is one.
- [x] **6.2 Money transfer.** `TransferService.transfer(from, to, amount)`: `$inc` two
      `sample_analytics.accounts` documents and insert a `transactions` audit document, all
      under `@Transactional`. Throw after the first write to prove the rollback.
- [x] **6.3 Guard rails.** Reject a transfer that would push a balance negative, and assert
      nothing was written.
- [x] **6.4 Collection creation.** Turned out to be a non-issue on a modern server: since
      MongoDB 4.4 the collection is created implicitly, so on the 8.0 lab it just works. Kept as
      an executable note (`writeToNewCollection`), because the old advice is still everywhere.
- [x] **6.5 Write and read concern.** `majority` / `snapshot` set through the transaction
      manager options; note what causal consistency buys.
- [x] **6.6 Retries.** Handle the `TransientTransactionError` and
      `UnknownTransactionCommitResult` error labels. The commit is retried, so the callback
      must be idempotent; use `MongoTemplate.withSession` / the driver's `withTransaction`
      or an explicit retry wrapper.
- [x] **6.7 Concurrency test.** Two threads transferring in opposite directions; assert the
      total balance is invariant. This is what actually proves the transaction works.
- [x] **6.8 When not to.** For the write-up: a single-document update is already atomic, and
      transactions cost roughly an order of magnitude more. Model to avoid them first.

**Done when:** the rollback test and the concurrent-transfer test both pass against the lab.

## Phase 7 - change data capture (change streams)

- [x] **7.1 Raw change stream.** `MessageListenerContainer` plus `ChangeStreamRequest` on
      `sample_analytics.accounts`, logging `operationType`, `documentKey`, `fullDocument`.
- [ ] **7.2 Reactive variant (optional) - NOT DONE.** Would need
      `spring-boot-starter-data-mongodb-reactive` alongside the blocking stack for the same
      demonstration. Left open deliberately.
- [x] **7.3 Filtering.** Server-side `$match` on `operationType` and on a document field -
      filter in the pipeline, never in Java.
- [x] **7.4 Before and after images.** Turn on `changeStreamPreAndPostImages` for the
      collection and use `FullDocumentBeforeChange.WHEN_AVAILABLE` to build a real audit diff.
- [x] **7.5 Resume tokens.** Persist the `_id` resume token per listener in a `cdc_offsets`
      collection after each batch and resume with `resumeAfter`/`startAfter` on restart.
      Kill the app mid-stream and prove no events were lost.
- [x] **7.6 Invalidate and the oplog window.** Handle `invalidate` events (collection dropped)
      and the `ChangeStreamHistoryLost` error when the stored token predates the oplog.
- [x] **7.7 Downstream sink.** Project each change into a denormalised `account_summary`
      collection - a miniature materialised view, which is the usual reason to run CDC.

**Done when:** a test writes documents, waits on a latch inside the listener, asserts the
events, then restarts the container from a stored token and asserts it resumes in the right place.

## Phase 8 - Atlas Search

The `movies_search` index (dynamic=false, typed fields including a `title` autocomplete) is
created by the seed container - see `docker/seed/search-indexes.js`.

- [x] **8.1 Basic `$search`.** `Aggregation.stage("{ $search: { index: 'movies_search', text: { query: ?0, path: ['title','plot'] } } }")`.
      Untyped stage strings are the pragmatic route; Spring Data has no typed `$search` builder.
      Endpoint `GET /search/movies?q=`.
- [x] **8.2 Score and highlight.** `$meta: "searchScore"` via `$addFields`, plus `highlight` in
      the search stage and `$meta: "searchHighlights"` in the projection.
- [x] **8.3 Autocomplete.** The `autocomplete` operator on `title` for type-ahead; contrast
      latency and result quality against the `regex` filter from 2.2.
- [x] **8.4 Compound query.** `compound` with `must` / `should` / `filter` / `mustNot` and a
      `boost` on title matches. Show how `filter` differs from `must` for scoring.
- [x] **8.5 Facets.** `$searchMeta` with `facet` over `genres` and year buckets, for sidebar
      counts alongside the results.
- [x] **8.6 Paging search results.** `searchAfter` / `searchSequenceToken` instead of `$skip`.
      Note: no explicit `sort` - sorting on `_id` needs it indexed as sortable, and the default
      relevance order works with `searchAfter` anyway.
- [x] **8.7 Fuzzy.** `maxEdits: 1` finds nothing for "Godfathar" and `2` does, because the
      edit distance is measured against the stemmed term - see `docs/notes/08-atlas-search.md`.
      Synonym mappings were not wired up; the fuzzy finding was the more useful half.
- [x] **8.8 Portability note.** Record what carries over from the local Atlas image to a real
      cluster (index definitions and query syntax do; search tiering, analyzers beyond the
      bundled Lucene set, and index-build behaviour do not).

**Done when:** searching `gangster` ranks The Godfather above loosely related titles, asserted
in a test, and `$searchMeta` facet counts match a `$group` count over the same filter.

## Phase 9 - wrap-up

- [x] **9.1** Keep `README.md` accurate: compose up, run, curl each endpoint.
- [x] **9.2** One `docs/notes/` file per phase, carrying the measurements taken - not just prose.
- [x] **9.3** `http/requests.http` (IntelliJ HTTP client) covering every endpoint.
- [ ] **9.4 (optional) - NOT DONE.** Micrometer timings on the paging and search endpoints.
      The `paging-benchmark` endpoint covers the same ground on demand, so this is a nicety.

## What actually changed along the way

Six things the plan got wrong or did not anticipate, all now recorded in `docs/notes/`:

1. `spring.data.mongodb.uri` is deprecated at error level in Boot 4 - and fails silently (2).
2. `Criteria` has no keyless form, so `elemMatch` on an array of scalars is unusable (2).
3. A projected `id` is read back from `_id`, so aliasing it away yields null (2).
4. A multikey index cannot cover a projection of the array field itself (3.4).
5. Keyset paging needed its own index - the Phase 3 compound index starts with `genres` (4.7).
6. Atlas Search facets need `stringFacet`/`numberFacet` mappings, and fuzzy distance is
   measured against the stemmed term (8).

## Phase 10 - Spring Security

Added after the fact, and planned in its own file:
[plans/IMPLEMENT-SPRING-SECURITY.md](plans/IMPLEMENT-SPRING-SECURITY.md).

Same shape as the phases above - identity out of `sample_mflix.users`, refresh tokens expiring
through a TTL index, ownership as a `Criteria` rather than a check - with the findings in
[notes/09-security.md](notes/09-security.md).

## Order and effort

Phases 1 to 4 are the backbone and are worth doing in order. Phases 5, 6, 7 and 8 are
independent of each other once Phase 0 is in place - take them in whatever order is most
useful. Transactions and CDC both need the replica set, and Atlas Search needs mongot; all
three are already satisfied by the lab container, which is why it runs the atlas-local image
rather than plain `mongo`.
