# fix(gc-tuning): stop rebuilding the full payments table on every write

## What

`SettlementService.record()` called a `logAuditTrail()` that did `paymentRepository
.findAll()` — every row in the table — then rebuilt a `groupingBy` map and
concatenated a summary string, on every single `POST /payments`. Fixed to log only
the payment just recorded.

## Why

Ten minutes of steady load (`perf/steady-load.js`) against `POST /payments`, captured
in `gc-baseline.jfr`, confirmed this as a clean allocation-dominated GC problem:
782.96 MB/s allocation rate, 1,056 young-gen collections in 10 minutes, and Hibernate
persistence-context objects (`EntityHolderImpl`, `EntityEntryImpl`) scaling directly
with table size — the fingerprint of `findAll()` loading the entire table as managed
entities on every write. GC pause times stayed small throughout (22.5ms max), ruling
out pause time as the bottleneck; the cost showed up as p99 latency ballooning to
5.53 seconds (max 10.6s) as the table grew during the run. Full numbers and
classification reasoning are in `docs/gc.md`.

## Measured improvement
allocation rate: 782.96 MB/s -> 3.77 MB/s (-99.5%)

collection count: 1,056 -> 42 (-96.0%)

longest pause: 22.5 ms -> 8.65 ms (-61.6%)

p99 latency: 5.53 s -> 39.01 ms (-99.3%)

throughput: 47.07 req/s -> 50.00 req/s (target fully recovered,
0 dropped vs. 1,507 dropped)


See `docs/gc.md` §4 for the full comparison table and §2 for the classification
evidence.

## What this drops (read before merging)

The audit trail no longer reports network-wide exposure per merchant at the instant
each payment lands — only the single payment just recorded. If finance relied on
that real-time rollup, this needs a scheduled job to replace it before merge, not
just a removal. Confirm with finance first.

## Rollback

Revert this commit alone (`git revert <sha>`) to restore the `findAll()` +
`groupingBy` audit trail — no schema or API changes are involved, so a rollback is a
single clean revert with no data migration and no client-visible change either way.