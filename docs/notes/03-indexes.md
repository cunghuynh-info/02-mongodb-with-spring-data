# Phase 3 notes - compound and partial indexes

Measured against the seeded lab: `sample_mflix.movies`, 21,349 documents. Indexes are created by
`IndexConfig` at startup; `GET /api/indexes/movies` prints what exists.

## Before and after

| Query | Index | Keys examined | Docs examined | Returned |
|---|---|---|---|---|
| `genres=Crime, 1970<=year<=1980` sorted by year (Phase 2 baseline, no index) | none - `COLLSCAN` | 0 | **21,349** | 20 |
| same query, after `{genres:1, year:-1, "imdb.rating":-1}` | `movies_genres_year_rating` | 2,457 | 2,457 | 2,457 |
| same query, projecting only indexed fields | `movies_genres_year_rating` | 2,457 | **0** | 2,457 |
| `imdb.rating >= 9` | `movies_rating_gt7_partial` | 30 | 30 | 30 |
| `imdb.rating >= 1` | none - `COLLSCAN` | 0 | **21,349** | - |
| `rated = "R"` | none - `COLLSCAN` | 0 | 21,349 | - |

The line that matters is the third: **0 documents examined**. Every field the projection asks
for is in the index, so the server answers from the index alone and never reads a document.

## Index sizes (3.5)

| Index | Bytes |
|---|---|
| `movies_rating_gt7_partial` (partial) | 77,824 |
| `movies_year_id` | 364,544 |
| `movies_genres_year_rating` | 659,456 |
| `_id_` | 1,024,000 |
| `cast_text_fullplot_text_genres_text_title_text` (ships with the sample data) | 17,186,816 |

The partial index is ~8x smaller than the compound one because it only holds the documents whose
rating exceeds 7. Worth noticing separately: the text index that comes with the dataset is
17 MB, 26x the compound index - which is the cost Phase 8 avoids by moving search to mongot.

## ESR, and the prefix rule

`{genres: 1, year: -1, "imdb.rating": -1}` is ordered **E**quality, **S**ort, **R**ange. Genres is
matched exactly, year is what the query sorts by, rating is a range. Get the order wrong and the
server can still use the index but has to add an in-memory `SORT` stage on top.

An index is only usable from its leading field inward. A query on `year` alone cannot touch
`movies_genres_year_rating` at all, however obviously `year` appears in it - which is exactly why
Phase 4 had to add `movies_year_id` for the paging sort. Two access patterns, two indexes.

## The partial-index trap (3.6)

A partial index serves a query only when the query's predicate **implies** the index's filter.

- `imdb.rating >= 9` implies `imdb.rating > 7` → index used, 30 keys examined.
- `imdb.rating >= 1` does not → collection scan, all 21,349 documents, even though the result
  set would have been a subset of the collection either way.

The planner is checking logical implication, not result-set size. Writing a query that "obviously
only returns highly rated films" is not enough; the predicate has to say so.

## Covering has a limit (3.4)

A multikey index cannot cover a projection of the array field itself. `movies_genres_year_rating`
is multikey because `genres` is an array: the index holds one entry per element, not the array,
so the server has to fetch the document to rebuild it. Projecting `{year, imdb.rating}` is
covered; adding `genres` back is not. Asserted both ways in `IndexConfigIT`.

## Unique, TTL, wildcard

- **Unique partial** (3.7) on `lab_subscribers.email` where `email` exists. This is the modern
  replacement for a sparse unique index: any number of documents without an email coexist, two
  with the same email do not.
- **TTL** (3.8) on `lab_sessions.createdAt`, 10 minutes. The reaper runs about once a minute, so
  expiry means "at least this long", never exactly.
- **Wildcard** (3.8) on `lab_sessions.attributes.$**`, for an attribute bag whose field names are
  data and cannot be enumerated in advance.
