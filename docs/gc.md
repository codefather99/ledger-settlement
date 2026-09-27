# GC Tuning Under Load — Ledger Settlement Service

Branch: `feature/gc-tuning`. Every number below comes from a 10-minute steady run of
`perf/steady-load.js` against `POST /payments`, the busiest endpoint — never a cold
JVM.

**Reproducibility precondition:** the `payments` table was truncated to 0 rows
immediately before each capture below (`TRUNCATE TABLE payments;`). This matters
because the defect under test scales with table size — a run against an
already-populated table gives a different (in this case, far worse) result than a
fresh table. Anyone reproducing this should truncate first.

## 0. Starting point

```
java -XX:+PrintFlagsFinal -version | Select-String -Pattern 'MaxHeapSize','UseG1GC'
```

size_t MaxHeapSize = 8510242816 {product} {ergonomic}
size_t SoftMaxHeapSize = 8510242816 {manageable} {ergonomic}
bool UseG1GC = true {product} {ergonomic}

java version "25.0.1" 2025-10-21 LTS


G1GC and ~8.5 GB max heap, both JVM ergonomic defaults (roughly a quarter of this
machine's RAM) — nothing hand-tuned going in.

## 1. Baseline capture (buggy `logAuditTrail`, table truncated to 0 before the run)

k6 results: 30,001 requested, 28,494 completed, 1,507 dropped (k6 ran out of VUs
waiting for slow responses), 0% failures on completed requests. Throughput dropped
to 47.07 req/s against a target of 50.

| Metric | Value |
|---|---|
| Total allocation rate (sampled) | **782.96 MB/s** (492.6 GB total over the 10-minute run) |
| Collection count | **1,056** young-gen GCs (`jdk.YoungGarbageCollection`); 1,060 `jdk.GarbageCollection` events total |
| Longest pause | **22.5 ms** |

Top allocating classes by sampled bytes (10 minutes, `jdk.ObjectAllocationSample`
weight):

1. `byte[]` — 99,025 MB
2. `int[]` — 61,383 MB
3. `java.lang.Object[]` — 44,693 MB

Also notable, and more specific evidence of the actual defect: `org.hibernate.
engine.internal.EntityHolderImpl` (17,602 MB) and `EntityEntryImpl` (16,554 MB) —
Hibernate's per-managed-row persistence-context tracking objects, allocated once per
row on every `findAll()` call. Their volume scales directly with table size, which is
the fingerprint of the bug: loading and tracking the entire table as managed entities
on every single write.

Request latency: avg 992ms, median 47ms, p90 4.9s, p95 5.29s, **p99 5.53s**, max
10.6s. The gap between median and tail is itself diagnostic — early requests (small
table) were fast; later requests (table approaching 30,000 rows) were catastrophically
slow, consistent with an O(n)-per-request cost against a growing table.

## 2. Classification

**Hypothesis:** `SettlementService.record()` calls `logAuditTrail()` on every write,
which does `paymentRepository.findAll()` — a full-table fetch — then rebuilds a
`groupingBy` map and concatenates a summary string, all per request, all thrown away
immediately after logging.

**Confirmed: this is allocation-dominated, not pause-dominated.**

- Allocation rate is 782.96 MB/s — a small number of classes (`byte[]`, `int[]`,
  `Object[]`, plus the Hibernate persistence-context classes) account for nearly all
  of it. That's the textbook allocation-pressure signature the lab describes.
- GC pause times stayed small throughout — 22.5ms longest pause is nowhere close to
  explaining a 10.6-second max request latency or a 5.53-second p99. Pausing is
  **not** the bottleneck.
- Collection count (1,056 young-gen GCs in 10 minutes, roughly 1.76/second) is driven
  by allocation *volume* forcing frequent young-gen collection, not by the collector
  struggling with pause time — each individual collection stayed fast.
- The presence of `EntityHolderImpl`/`EntityEntryImpl` — objects that only exist
  because Hibernate is tracking every row in the table as a managed entity — is
  direct evidence tying the allocation volume to `findAll()` specifically, not to
  request handling in general.

This matches the lab's allocation-pressure signature exactly: a few classes
dominating the allocation profile, with pause times remaining modest throughout.

## 3. The fix

`SettlementService.logAuditTrail()` no longer calls `findAll()`. It logs only the
payment just recorded — the auditor still gets a per-payment trail; the network-wide
exposure rollup this replaced would need to move to a separate scheduled job rather
than running on every write's request path. See the fix commit for the exact diff.

No heap or collector flag was changed — this was a code-only fix, per the lab's own
branching guidance for an allocation-dominated result.

## 4. Tuned re-run (same fix, table truncated to 0 before the run, identical load)

k6 results: 30,001/30,001 requests completed, 0 dropped, 0% failures, throughput
exactly at target (50.00 req/s).

| Metric | Baseline (buggy) | Tuned (fixed) | Δ |
|---|---|---|---|
| Allocation rate (sampled) | 782.96 MB/s | 3.77 MB/s | **−99.5%** |
| Collection count (young-gen) | 1,056 | 42 | **−96.0%** |
| Longest pause | 22.5 ms | 8.65 ms | −61.6% |
| p99 request latency | 5.53 s | 39.01 ms | **−99.3%** |
| Throughput | 47.07 req/s (1,507 dropped) | 50.00 req/s (0 dropped) | fully recovered |

Top allocating classes, tuned run: `byte[]` (716 MB), `jdk.internal.vm.StackChunk`
(251 MB, virtual-thread carrier overhead), `java.lang.Object[]` (134 MB) — ordinary
framework/HTTP allocation, no Hibernate persistence-context churn anywhere in the
top 10.

## 5. The trade, stated honestly

No throughput or startup-time regression — the tuned run hit its target rate exactly
with zero dropped iterations, versus the baseline's degraded 47.07 req/s and 1,507
drops. No memory-footprint regression either; the fix strictly removes work.

The real cost is functional, not performance-related: the audit trail no longer
reports network-wide exposure per merchant at the instant each payment lands — only
the single payment just recorded. A network-wide, real-time view would need a
separate scheduled job to replace what `findAll()` was doing inline. That's an
acceptable trade for this fix on its own — a 99%+ reduction in allocation pressure
and p99 latency in exchange for making an audit rollup periodic instead of real-time
— but finance should confirm the periodic rollup is acceptable before this merges,
since that's a genuine behavior change, not just a performance one.