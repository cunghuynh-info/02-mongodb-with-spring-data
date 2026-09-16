# Phase 4 notes - offset and keyset paging

`GET /api/movies/paging-benchmark?yearFrom=1900&size=20&deepPage=500` against the seeded lab:
21,312 movies with a numeric year, sorted `{year: -1, _id: -1}`, both strategies served by
`movies_year_id`.

## The measurement

| | Keys examined | Docs examined | Notes |
|---|---|---|---|
| offset, page 500 (`skip 10000`) | **10,020** | 20 | every skipped key is walked and discarded |
| keyset, same position | **20** | 20 | one page, wherever you are |

Wall clock from the same run (single machine, warm):

| | find | count | total |
|---|---|---|---|
| offset page 0 | 57 ms | 29 ms | 86 ms |
| offset page 500 | 21 ms | 10 ms | 32 ms |
| keyset page 0 | - | - | 11 ms |
| keyset at page-500 depth | - | - | **2 ms** |

Two things the timings show that the "deep pages are slow" slogan does not:

1. **`docsExamined` is identical.** Both read exactly 20 documents. The index supplies the sort,
   so the damage from `skip` is entirely in *index keys walked* - which is why `keysExamined` is
   the column to watch, and why a `docsExamined`-only reading of an explain misses it.
2. **The count query is a second, separate cost.** `Page` runs `countDocuments` on every request,
   10-29 ms here on top of the find. `Slice` (4.3) drops it, and keyset never had it.

At 21k documents the absolute numbers are small. The shape is what matters: offset's key count
grows linearly with depth and keyset's does not, so the gap is a function of how deep your users
actually go.

## What keyset costs you

- No total count and no page numbers. "Page 1 of 1,066" and "jump to page 500" are not
  expressible - that is the trade, not an oversight.
- The sort key must be **unique**. `{year: -1}` alone is ambiguous for two films from the same
  year, so the page boundary can move between requests and a row gets duplicated or skipped.
  `_id` as the final tiebreaker is what fixes it.
- The sort key must be **consistently typed**. MongoDB brackets comparisons by BSON type, so the
  `sample_mflix` documents storing `year` as a string are invisible to a `year < x` predicate and
  never appear on any page after the first. The tests filter with `yearFrom=1900` for exactly
  this reason; on a real system, page on a field you control.

## Two implementations

`GET /api/movies/scroll` uses Spring Data's `Window` / `ScrollPosition.keyset()`;
`GET /api/movies/keyset` builds the same thing by hand so the predicate is visible:

```
(year < :y) OR (year = :y AND _id < :id)
```

A test walks the whole collection with each and asserts they return identical titles, with no
duplicates and no gaps.

One wrinkle in the Spring version: the position map is keyed by **property** name, so the
tiebreaker is `id`, not `_id`. Passing `_id` fails with *"KeysetScrollPosition does not contain
all keyset values"*, which is not an obvious way to say "wrong key name".

## The cursor

`CursorCodec` base64s `year|hex-id`. It is not encryption and not tamper-proof - the point is
that the sort key stops being part of the published API. A client that learned to pass
`?afterYear=1999&afterId=...` would have to be migrated the day the sort changes; a client
holding an opaque string does not.
