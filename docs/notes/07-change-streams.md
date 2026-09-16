# Phase 7 notes - change data capture

`AccountChangeListener` watches `sample_analytics.accounts` through a
`MessageListenerContainer`, maintains a denormalised `account_summary` view, and stores a resume
token after every event.

## What was verified

On the lab: start the stream, run one transfer, and exactly **two** events arrive - the two
balance `$inc`s - with a resume token persisted. Then:

- The **rolled-back** transaction from Phase 6 produced **no events**. An aborted transaction is
  invisible downstream; there is nothing to compensate for.
- `account_summary` tracked both accounts with a `changeCount` and `lastOperation`.

In the test suite (`AccountChangeListenerIT`): stop the listener, write while it is down, restart
with `resume=true`, and the missed change still arrives. That is the whole point of storing the
token, and it is the part that is easy to get wrong without noticing - a listener that always
starts from "now" looks perfectly healthy right up until the first restart.

## Things that cost time to get right

**Registering a subscription returns before the cursor is open.** Anything written in that gap is
never seen. `startAndAwait` blocks on `Subscription.await(timeout)`; without it the tests were
intermittently green, which is worse than red.

**Save the token after handling, never before.** A crash between "saved token" and "did the work"
loses the event with no way to detect it. Saving after means a crash replays one event, and the
handler has to tolerate that - upserts, not blind increments, for anything that must be exact.
(`changeCount` here is a blind `$inc`, and is therefore approximate by construction. That is fine
for a debug counter and would not be for anything billed.)

**`resumeAfter` vs `startAfter`.** They differ only around an `invalidate` event - a dropped
collection. `startAfter` opens a new stream past it; `resumeAfter` refuses. Refusing is the safer
default because it surfaces the drop instead of silently continuing against a new collection.

**A listener that throws kills the subscription.** The handler catches and logs; a real one would
route the event to a dead-letter collection rather than dropping it.

**Filter in the pipeline, not in Java.** `$match` on `operationType` runs on the server. Shipping
every change to the JVM to discard most of it is the mistake the `filter` builder prevents.

## Before-images (7.4)

Off by default. `collMod` with `changeStreamPreAndPostImages: {enabled: true}` turns them on per
collection, and the stream asks for them `WHEN_AVAILABLE` rather than `REQUIRED` so it keeps
working when they are off. With them on, each event carries both the old and new document, which
is what makes a real audit diff possible - an update event alone tells you what a field *became*,
never what it *was*.

They are not free: the server stores the pre-images, and they have their own expiry policy.

## The oplog window (7.6)

A resume token points into the oplog. If the listener is down longer than the oplog retains, the
token is too old and the stream fails with `ChangeStreamHistoryLost`. There is no way to recover
the missed changes from the stream - the fallback is a full re-sync. Worth deciding up front how
long a listener may be down, and sizing the oplog to match.

## Not done

7.2, the reactive `Flux`/SSE variant, would need `spring-boot-starter-data-mongodb-reactive`
alongside the blocking one. The plan marks it optional and it adds a second stack for the same
demonstration, so it is left open.
