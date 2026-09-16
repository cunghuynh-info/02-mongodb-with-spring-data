# Phase 6 notes - transactions

Two accounts, 1000.00 each. `TransferService` does three writes in one transaction: `$inc` on
both balances and an audit insert into `transfers`.

## What was verified

Against the lab (accounts 900001 / 900002):

| Scenario | Balances after | Audit rows |
|---|---|---|
| transfer 250 | 750 / 1250 | 1 |
| transfer 100 then throw | **750 / 1250** (unchanged) | **1** (unchanged) |
| transfer 99,999 (overdraft) | **750 / 1250** (unchanged) | unchanged |

The rollback case is the one that proves the transaction is real: both `$inc`s and the insert had
already executed when the exception was thrown, and none of them survived.

The concurrency test is the stronger proof. Twenty threads transferring in both directions at
once; individual balances are non-deterministic, but the **sum is invariant** and the number of
audit rows equals the number of committed transfers. No partial commits, no lost updates.

A detail worth noticing, from Phase 7 watching at the same time: the rolled-back transaction
produced **no change-stream events at all**. An aborted transaction is invisible downstream, not
visible-then-retracted.

## Configuration that matters

The transaction manager is built from `analyticsTemplate`'s own `MongoDatabaseFactory`, not the
primary one. `MongoTransactionManager` binds the session to a specific factory, and work done
through a template built on a *different* factory runs outside the transaction - no error, no
rollback, no warning. That is the failure mode to watch for in a multi-database setup.

`readConcern: SNAPSHOT` + `writeConcern: MAJORITY`. Snapshot means every read in the transaction
sees one consistent point in time, which is what makes the read-balance-then-check-then-write
sequence in `transfer()` safe rather than a race. Majority means a failover cannot roll back an
acknowledged commit.

## Retries (6.6)

MongoDB labels the failures it considers retryable: `TransientTransactionError` (replay the whole
transaction) and `UnknownTransactionCommitResult` (re-send the commit). The driver retries the
commit on its own; nothing retries the callback, which is why `RetryingTransferService` sits
**outside** the `@Transactional` boundary rather than inside it.

The callback must therefore be safe to run twice. `transfer()` re-reads the balance and re-checks
the guard on every attempt, so a replay either succeeds cleanly or fails the guard - it can never
apply the same `$inc` twice, because an aborted attempt left nothing behind.

`isRetryable` walks the cause chain looking for the server's error **labels**, not message text.

## Two things that are no longer true

- **"Create your collections before the transaction."** Since MongoDB 4.4 the server creates them
  implicitly; on the 8.0 lab `writeToNewCollection` simply works. The advice survives in a lot of
  documentation and now only applies to sharded collections and older servers.
- **Self-invocation.** `transferThenFail` calls `transfer` directly, so the inner
  `@Transactional` is bypassed entirely - the proxy is not in the call path. Harmless there
  because the outer boundary is already open, but it is exactly how people end up with a method
  they believe is transactional and is not.

## When not to (6.8)

A single-document update is already atomic, and a transaction costs roughly an order of magnitude
more. If both balances lived in one document, none of this would be needed. Model to avoid the
transaction first; reach for one when the invariant genuinely spans documents.
