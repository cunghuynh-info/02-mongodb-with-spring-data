# Phase 8 notes - Atlas Search

The `movies_search` index is created by the seed container (`docker/seed/search-indexes.js`) and
by `SearchIndexService` for tests. `dynamic: false` with an explicit field list - a dynamic index
covers everything including the 4 KB `fullplot` of all 21,349 documents.

## What it returns

`GET /api/search/movies?q=gangster`:

```
Gangster                                2006  score=7.38
Gangster No. 1                          2000  score=6.53
Gangster Squad                          2013  score=5.53
Saheb Biwi Aur Gangster Returns         2013  score=5.23
Nameless Gangster: Rules of the Time    2012  score=4.84
```

`GET /api/search/autocomplete?q=godf` returns the Godfather films from a four-letter prefix -
matched against edge-grams built at index time, not scanned. Contrast with the Phase 2.2
`regex` filter, which is unanchored and therefore reads every title in the collection.

`GET /api/search/facets?q=gangster` returns 144 matches with genre and decade counts
(Crime 104, Drama 80, Action 54, ...) in one `$searchMeta` call that never materialises a
document.

## Things that cost time to get right

**Facets need their own field types.** `$searchMeta` facets only work on `stringFacet` /
`numberFacet`. A field indexed as a plain `string` is queryable but not facetable, and the error
does not make that obvious. `genres` and `year` are indexed **twice**, once for matching and once
as a facet type.

**Editing an index definition does nothing unless you update it.** The seed script originally
skipped indexes that already existed, so the facet types never took effect on a re-run. It now
calls `updateSearchIndex`. Same trap in Docker: `search-indexes.js` is `COPY`d into the seed
image, so a plain `docker compose up` runs the old script - `--build` is required.

**Sorting needs a sortable field.** Adding `"sort": {"_id": 1}` to `$search` fails with
*`_id is not indexed as sortable`*. `searchAfter` works fine on the default relevance order, so
the sort clause was removed rather than indexing `_id`.

**Queryable is not the same as caught up.** `createSearchIndex` returns immediately and the index
reports `queryable` before mongot has finished ingesting. Querying in that window returns an
empty result, not an error - which looks exactly like "my query is wrong". The tests poll until a
known document actually comes back.

## Fuzzy matching is measured against the stem (8.7)

The surprising one:

| Query | maxEdits | Result |
|---|---|---|
| `Godfathar` | 1 | `[]` |
| `Godfathar` | 2 | Godfather, The Godfather, Tokyo Godfathers |

"Godfathar" is one character from "Godfather", so `maxEdits: 1` looks like it should match. It
does not, because the edit distance is computed against the **indexed term**, and `title` uses
`lucene.english`, which stems "Godfather" to `godfath`. Verified directly: searching `godfath`
and `godfathers` both hit the same documents. `godfathar` is two edits from `godfath`.

The lesson generalises: with a stemming analyzer, the fuzzy budget is spent partly on the
stemming itself. A field you intend to fuzzy-match is usually better indexed with a non-stemming
analyzer as a second mapping.

## compound: filter vs must (8.4)

- `filter` narrows without contributing to the score
- `must` narrows **and** scores
- `should` only scores (this is where `boost` goes)
- `mustNot` excludes

Putting a genre restriction in `must` lets it distort the ranking; in `filter` it cannot. The
distinction is invisible until you wonder why adding a filter reordered your results.

## Portability to a real Atlas cluster (8.8)

Carries over unchanged: index definitions, every query in `SearchService`, the analyzers used
here, `$searchMeta`, `searchAfter`.

Does not: search node tiering and sizing, index build times at real scale, cross-region
replication, and the Atlas UI's index builder. Also worth knowing that the local image runs
mongot in the same container, so its performance says nothing about a cluster with dedicated
search nodes.
