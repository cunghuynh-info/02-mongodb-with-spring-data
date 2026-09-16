# Phase 1 notes - embedding and referencing

Measured against the seeded lab: `sample_mflix` with 21,349 movies (avg 1,598 bytes) and
41,086 comments (avg 284 bytes). Neither collection has an index beyond `_id` yet - Phase 3
changes that, and every number here should be re-measured afterwards.

## When each one wins

| | Embed | Reference |
|---|---|---|
| Cardinality | 1:few, with a known ceiling | 1:many, unbounded |
| Read pattern | always read with the parent | read on its own, or paged |
| Write pattern | changes when the parent changes | written independently, often concurrently |
| Cost of getting it wrong | document growth, 16 MB wall, rewriting the whole document per update | an extra round trip, or a `$lookup` |

In this model `imdb`, `awards` and `tomatoes` are embedded - they are part of what a movie *is*,
they are bounded, and nothing updates them alone. Comments are referenced: the most-commented
film in the dataset has 161 of them, they arrive one at a time from different users, and
embedding them would mean rewriting a 1.6 KB document on every new comment.

## The three read strategies

All three produce the same payload for `GET /api/movies/{id}/comments/...`. Timings are
10 calls after a warm-up, end to end over HTTP, on a movie with 161 comments, `limit=10`:

| Strategy | Queries | ms/call | Notes |
|---|---|---|---|
| `via-lookup` (1.4) | 1 | 32.9 | sorts and limits inside the sub-pipeline, server-side |
| `via-reference` (1.3) | 2 | 48.0 | `@DocumentReference` - reads like a field, cannot page or limit |
| `manual` (1.2) | 2 | 48.3 | two explicit, individually indexable, individually pageable queries |
| `via-eager-reference` (1.3) | n+1 | 79.6 | each `CommentRef` resolves its movie separately |

Takeaways:

- `$lookup` is the fastest here precisely because the `$sort` + `$limit` run on the server.
  The other two ship the whole comment list back and cut it in Java.
- `@DocumentReference` is the most convenient and the least controllable. There is no way to
  page it, and a lazy reference will fire a query from anywhere that touches the getter -
  including `toString`, `equals`, and Jackson. That is why `Movie.comments` is excluded from
  Lombok's generated methods and why the controllers return DTOs, never entities.
- The eager inverse is the N+1 everyone warns about, and the measurement bears it out: it is
  the slowest despite fetching the same 10 comments.
- Every one of these numbers is inflated by the missing index on `comments.movie_id` - each
  resolution scans all 41k comments. See Phase 3.

## Extended reference (1.5)

`Movie.recent_comments` keeps the 5 newest comments on the movie itself, maintained by

```
{ $push: { recent_comments: { $each: [ ... ], $slice: -5 } },
  $inc:  { num_mflix_comments: 1 } }
```

Verified on the lab: after 7 posts the array holds exactly the last 5 and the counter
advances by 7, while the full list stays complete in `comments`.

What it buys: the movie detail screen renders with one read. What it costs: a second write per
comment, and a duplicated `name`/`text` that will go stale if a comment is ever edited in place.
Bounded is doing all the work in that sentence - `$slice` is what keeps the document from
growing with traffic.

`num_mflix_comments` is the same idea applied to a single number, and it turns out to matter
for more than display: the Phase 2.7 `$lookup` pipeline uses it to narrow candidates before
joining, without which the query does not return at all (see `MovieAggregationService`).
