# Phase 2 notes - Criteria, MongoTemplate, aggregation

## Things that bit, and are now guarded by tests

**`spring.data.mongodb.uri` is deprecated at error level in Spring Boot 4.** The replacement is
`spring.mongodb.uri`. Getting it wrong is silent: the driver falls back to
`mongodb://localhost/test`, every collection is empty, and every endpoint returns `[]` with no
error anywhere. `LabStartupCheck` exists to turn that into a warning at startup.

**Spring's `Criteria` has no keyless form.** `Criteria.where("cast").elemMatch(new Criteria().regex(..))`
compiles, renders as `{"cast": {"$elemMatch": {}}}`, and matches nothing. For a single condition
on an array of scalars the plain predicate is already correct - MongoDB matches any element -
so `cast` uses `regex` directly. `$elemMatch` earns its place in `SalesQueryService`, where two
conditions have to hold for the *same* array element; there the subfields are named and the API
works. Covered by `MovieCriteriaBuilderTest.castMatchesAnyArrayElementWithoutElemMatch`.

**A projected `id` is read back from `_id`.** `project(...).and("_id").as("id").andExclude("_id")`
produces exactly the BSON you would write by hand, and still maps to `null`: Spring treats an
`id` property as the entity identifier and looks for it under `_id`. Keep `_id` in the
projection instead.

**Type ordering decides your "top" list.** BSON sorts strings above every number, and a few
`sample_mflix` documents store `imdb.rating` as `""`. Sorting by rating descending puts those
first. The fix is a type filter, and `gt(0)` is one - comparison operators are type-bracketed,
so it drops the strings without a `$type` stage.

**`$lookup` without an index on the foreign field is quadratic.** `sample_mflix.comments` ships
with only an `_id` index, so joining ~6,000 candidate movies means ~6,000 scans of 41k comments.
The unfiltered `mostCommented` pipeline did not return within two minutes. Narrowing the input
with the denormalised `num_mflix_comments` counter first brings it to **349 ms**. Phase 3 adds
the index that makes the prefilter optional.

## Aggregation context: typed vs untyped

`mongoTemplate.aggregate(agg, Movie.class, X.class)` validates every field reference against the
entity, which rejects stages that invent fields (`commentCount`). The pipelines here use
`aggregate(agg, "movies", X.class)` - untyped input context, typed output mapping - so raw
stages and computed fields pass through, at the cost of losing Java-property-name translation.

## Explain baseline (2.9)

`GET /api/movies/explain?genres=Crime&yearFrom=1970&yearTo=1980&size=20` against the full
collection, before any index exists:

| | |
|---|---|
| winning stage | `SORT` |
| index used | none |
| returned | 20 |
| keys examined | 0 |
| **docs examined** | **21,349** |
| execution time | 15 ms |

Every document in the collection read to return twenty. This is the "before" row for Phase 3.3;
re-run the same URL after creating `{ genres: 1, year: -1, "imdb.rating": -1 }` and the
`docsExamined` column is the one to watch.

## Left for later

- `$dateTrunc` and `$densify` (2.8). The revenue rollup groups by `$year`/`$month` instead,
  which is enough for the shape of the answer and avoids the newer operators; the gap-filling
  variant is still worth writing.
