# Phase 5 notes - bulk write

## The measurement (5.3)

`GET /api/analytics/bulk/benchmark?total=20000&batchSize=1000` against the lab:

| | Time |
|---|---|
| 20,000 documents, one `insert()` per document | **60,298 ms** |
| the same 20,000 in batches of 1,000 | **564 ms** |
| speedup | **107x** |

A minute versus half a second. Every one of those 20,000 individual inserts paid a full network
round trip and waited for its own acknowledgement; the batched version paid 20.

This is the entire justification for the API, and it is worth having the number rather than the
intuition - "bulk is faster" does not tell you whether it is worth restructuring a job, and
"100x" does.

## Ordered vs unordered (5.2)

`GET /api/analytics/bulk/duplicate-key` runs the same three-document batch twice against
`lab_subscribers`, which has a unique partial index on `email`. The second document duplicates
the first.

| Mode | Documents written | Third document | Outcome |
|---|---|---|---|
| `ORDERED` | 1 | never attempted | `BulkOperationException` (1 write error) |
| `UNORDERED` | 2 | written | `BulkOperationException` (1 write error) |

Both **report** the failure - unordered does not swallow it. The difference is only whether the
rest of the batch is attempted. Unordered is also what lets the server parallelise, so it is the
right default unless the writes genuinely depend on each other in sequence.

One trap: `BulkOperationException` is **not** a `DataIntegrityViolationException`. It sits
directly under `DataAccessException`, so a catch block written for the usual duplicate-key
exception silently misses it. `getErrors()` gives one entry per failed write, each carrying its
original index in the batch, which is what you need to report *which* rows failed.

## Result counts (5.1)

`BulkWriteResult` distinguishes things that are easy to conflate:

- `matched` vs `modified` - an update that sets a field to the value it already has matches but
  does not modify. A job reporting "0 modified" may be working perfectly.
- `upserted` is a separate list, not part of `inserted`.

Asserting these exactly in a test is what catches a query that quietly matched nothing.

## Idempotent upsert (5.5)

`$setOnInsert` for `createdAt` and the opening balance, `$set` for `updatedAt`. Replaying the
batch reports `upserted: 0, matched: 2` and leaves both `setOnInsert` fields untouched - asserted
in `BulkWriteServiceIT`. This is what makes a retried job safe.

## Streaming in, batching out (5.6)

`denormaliseCommentAuthors` reads `comments` through `mongoTemplate.stream()` - a server-side
cursor, not a materialised list - and accumulates writes into batches on the way past. Memory
stays flat regardless of collection size. `find()` into a `List` would have to hold all 41,086
comments at once, and that is the version that works in dev and dies on the real collection.
