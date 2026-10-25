# JMH Benchmark Suite: Ledger Settlement Service

- **Run date:** 2026-09-28
- **JVM:** Java HotSpot(TM) 64-Bit Server VM 25.0.1+8-LTS-27, no extra VM options
- **JMH:** 1.37, one benchmark thread, Blackhole mode `compiler` (auto-detected by JMH on this JVM)
- **Hardware:** Intel Core i9-13900HX (24 cores, 32 threads), 31.7 GB RAM, Windows 11 Pro 10.0.26200. Power plan: Balanced when checked on 24 Oct 2026; the plan active during the 28 Sep run was not recorded. See `benchmarks/machine.txt`.
- **Evidence:** `benchmarks/results.json` is the raw output of the final run. The other logs are listed in section 8.

Every number below is a score plus or minus its error. The error is JMH's 99.9% confidence half-width. A comparison whose intervals overlap is marked **inconclusive**, not ranked.

## 1. Results at a glance

Final run: `LedgerBenchmarks`, 3 forks x (5 warmup + 10 measurement iterations of 1 s), `-prof gc`. Each row is 30 measured iterations. Values are read from `results.json`, not from the rounded console output.

| Benchmark | Time per op | 99.9% interval (µs) | Allocated per op | Allocation rate |
|---|---|---|---|---|
| `mapToResponse` | 0.00427 ± 0.00005 µs (4.27 ± 0.05 ns) | [0.00422, 0.00432] | 40.00 ± 0.00 B | 8,927 ± 108 MB/s |
| `entityToString` | 0.0836 ± 0.0008 µs (83.6 ± 0.8 ns) | [0.0828, 0.0844] | 256.00 ± 0.00 B | 2,921 ± 27 MB/s |
| `calculateSettlement` (1,000 payments) | 0.618 ± 0.043 µs (618 ± 43 ns) | [0.576, 0.661] | 237.3 ± 17.9 B | 374 ± 50 MB/s |

The allocation errors for the first two rows are below the display precision (about 5e-6 B and 4e-7 B). JMH prints MB as 2^20 bytes.

## 2. The three methods and why each was chosen

All three are real methods from the service. None is invented for the benchmark.

**`PaymentResponse.from(PaymentEntity)`: the mapping method.** It turns the JPA entity into the API response record. It runs on every `POST /payments` (`PaymentResponse.from(saved)`) and every `GET /payments/{id}`. It is pure (no I/O), so a microbenchmark measures the mapping itself. Its result is a new object, which makes it an easy place to see dead code elimination.

**`PaymentEntity.toString()`: the string-heavy method.** It concatenates five fields, including formatting an `Instant`, into one string. Honest note: nothing on the request path calls it today, because `logAuditTrail` logs the fields individually. It was still the best real string-heavy method in this codebase, and it is what any future `log.info("{}", entity)` or debug print would cost. There is no collection-processing method other than the settlement calculation, so the collection-heavy option is covered by the third benchmark.

**`SettlementService.calculateSettlement(List<PaymentEntity>)`: the settlement calculation.** It sums the amounts of a merchant's payments and subtracts the fee. It is on the path of `GET /payments/settlement`. To benchmark it without a database, I extracted it from `settlementFor()`, which now fetches the payments and delegates to it. That is the only change to production code (the method is `public`, behaviour unchanged).

A note on the lab's background text, which says this calculation "stays on long minor units and BigDecimal". In this service `BigDecimal` is used once, at construction, to convert the configured fee rate 0.031 to an exact 310 basis points (`toBasisPoints`). The per-call path is pure `long` arithmetic (`gross * feeRateBasisPoints / 10_000`) plus a stream sum. No `double` appears anywhere, so the benchmark measures `long` arithmetic and the stream, not `BigDecimal`.

## 3. Dead code elimination: the broken benchmark and the fix

The deliberately wrong benchmark computes the mapping and discards the result:

```java
@Benchmark
public void mapToResponse_discardsResult() {
    PaymentResponse.from(ENTITY);
}
```

Both this step and the fixed one used reduced settings (3 warmup + 5 measurement iterations of 2 s, 1 fork), because they only demonstrate the effect. The input was a `static final` constant in both, so only the use of the result differs.

| Variant | Score | Evidence |
|---|---|---|
| Broken (result discarded) | ≈ 10⁻⁴ µs/op (about 0.1 ns) | `benchmarks/broken-run.txt` |
| Fixed: returns the result | 0.004 ± 0.001 µs/op, interval [0.003, 0.004] | `benchmarks/fixed-run.txt` |
| Fixed: `Blackhole.consume(...)` | 0.004 ± 0.001 µs/op, interval [0.003, 0.004] | `benchmarks/fixed-run.txt` |

**Size of the error:** the broken benchmark reported a cost about **40 times too low**. JMH printed the broken value only as `≈ 10⁻⁴`, so the factor is an order of magnitude, not a precise ratio. 0.1 ns is less than one clock cycle at desktop speeds (a 3 to 5 GHz core takes 0.2 to 0.33 ns per cycle). Allocating a five-field record cannot take that little time, so the JIT had deleted the call. The broken benchmark timed an empty loop.

## 4. Inputs, configuration, and why forks matter

All inputs live in `LedgerBenchmarks.LedgerState`, annotated `@State(Scope.Benchmark)`. Its non-final fields are filled in an `@Setup` method with `new Random(42)` and a fixed `Instant`, so runs are repeatable and the JIT cannot fold the inputs into the measured code. The settlement input is a list of 1,000 payments for one merchant. Every benchmark returns its result.

Configuration on the class: `@BenchmarkMode(Mode.AverageTime)`, `@OutputTimeUnit(TimeUnit.MICROSECONDS)`, `@Warmup(iterations = 5, time = 1 s)`, `@Measurement(iterations = 10, time = 1 s)`, `@Fork(3)`.

**Why forks matter:** each fork is a fresh JVM that makes its own JIT and memory-layout decisions, so one JVM's lucky or unlucky choices show up as run-to-run variance instead of being mistaken for the true cost. Section 5 shows a real case.

**Deviation from JMH defaults:** JMH's default iteration time is 10 s, which would have made the suite take about 22 minutes. I set 1 s per iteration. These operations run millions of times per iteration, so 1 s is ample, but it is a choice, and longer iterations could average over slower drifts (see limits).

## 5. Per-fork detail for `calculateSettlement`

The pooled result in section 1 hides that the three forks did not behave alike:

| Fork | Mean time (µs/op) | Range over 10 iterations | Allocated (B/op) |
|---|---|---|---|
| 1 | 0.575 | 0.567 to 0.594 | 256.0 |
| 2 | 0.573 | 0.566 to 0.585 | 256.0 |
| 3 | 0.706 | 0.693 to 0.727 | 200.0 |

Facts from the run:
- Fork 3 started at 0.590 µs/op in warmup iteration 1, jumped to 0.721 in warmup iteration 2, and stayed between 0.69 and 0.73 for every measured iteration.
- Every iteration in fork 3 is slower than every iteration in forks 1 and 2, so this is a real difference between forks, not noise within one.
- Fork 3 allocated 56 fewer bytes per call (200 vs 256). Background load cannot change how many bytes a call allocates, so fork 3 ran differently compiled code.

Hypothesis, not verified: the JIT made a different inlining or escape-analysis decision in that JVM, removing one 56-byte object of the stream pipeline. Confirming it would need compilation logs (`-XX:+PrintCompilation`, `-XX:+PrintInlining`) or assembly output, which this suite did not collect.

The earlier timing-only run of the same jar (section 6) had no slow fork. Its 30 iterations were 27 between 0.561 and 0.589 µs/op, plus three slow iterations (0.622, 0.664, 0.896) in fork 1 that then recovered. So whether a slow JVM appears varies between runs of the same jar. A single fork would have shown either 0.57 or 0.71 with a tight error and no sign of the other.

The ± 0.043 in section 1 is the pooled figure and includes this fork-to-fork difference. JMH computes it treating all 30 iterations as independent samples from one normal distribution. Neither assumption holds here, because iterations within a fork are correlated and the data is bimodal, so the interval is best read as a rough guide.

Sanity checks on the allocation figures: `mapToResponse` allocating 40 B matches the object layout (12 B header + four 4 B references + one 8 B `long` = 36 B, rounded up to 40 B, assuming compressed references). `calculateSettlement` allocating about 256 B for 1,000 payments is a fixed cost of the stream pipeline, not a per-payment cost.

## 6. Comparisons and verdicts

| Comparison | A | B | Verdict |
|---|---|---|---|
| Broken vs fixed (dead code elimination) | ≈ 10⁻⁴ µs/op | 0.004 µs/op, [0.003, 0.004] | **Different**: broken is about 40x too fast |
| Fixed by returning the result vs by `Blackhole` (reduced runs) | 0.004 ± 0.001, [0.003, 0.004] | 0.004 ± 0.001, [0.003, 0.004] | **Inconclusive**: identical intervals |
| `static final` input vs `@State` input, `mapToResponse` (reduced runs) | 0.004 ± 0.001, [0.003, 0.004] | 0.004 ± 0.001, [0.004, 0.004] | **Inconclusive**: intervals overlap at the printed precision |
| `-prof gc` off vs on, `calculateSettlement` (full config) | 0.586 ± 0.041, [0.545, 0.628] | 0.618 ± 0.043, [0.576, 0.661] | **Inconclusive**: intervals overlap |
| `-prof gc` off vs on, `entityToString` (full config) | 0.083 ± 0.002, [0.082, 0.085] | 0.084 ± 0.001, [0.0828, 0.0844] | **Inconclusive**: intervals overlap |
| `-prof gc` off vs on, `mapToResponse` (full config) | 0.004 ± 0.001, [0.004, 0.004] | 0.00427 ± 0.00005, [0.00422, 0.00432] | **Inconclusive** at the printed precision of the timing-only run (see note) |
| `calculateSettlement`, fork 3 vs forks 1 and 2 | 0.693 to 0.727 per iteration | 0.566 to 0.594 per iteration | **Different**: ranges do not overlap, and allocation differs (200 vs 256 B) |

`-prof gc` adds a small overhead. This suite could not detect any effect of it on timings; that is a statement about resolution, not proof that there is none. The timing-only values for the `-prof gc` rows come from the console output of `results-timing.json`'s run, which rounds to three decimals. The `mapToResponse` row cannot be resolved without reading that JSON's full-precision values.

## 7. Limits: what this suite does not tell you

1. **Behaviour under concurrency.** Every benchmark ran on one thread. The live service handles concurrent requests. Contention, false sharing, and how garbage collection scales when many threads allocate at once are not represented. For scale: `mapToResponse` allocated about 9 GB/s from a single thread, and nothing here says what several threads doing that do to the collector.
2. **Cache effects at production data sizes.** `calculateSettlement` ran on one list of 1,000 payments built back to back in `@Setup`, so the objects sit together in memory and the list fits in cache. A merchant's payments loaded by Hibernate are scattered across the heap, and real tables are far larger. The cost per payment (about 0.6 ns here) would likely rise. Only one size was tested, so the scaling with size is unknown.
3. **A benchmark harness is not a live request path.** None of these measure JSON parsing or serialisation, bean validation, Spring proxies and transactions, Hibernate loading, the database round trip, or the network. As an order of magnitude only: `mapToResponse` at 4.27 ns sits next to a median of about 4.28 ms for `POST /payments` in the earlier GC-tuning lab (a different lab and run, so not a like-for-like comparison). It is about a millionth of the request. A faster mapping method would not measurably speed up the endpoint. I expect the database fetch to dominate `GET /payments/settlement` in the same way, but I did not measure it.
4. **JIT variance and only three forks.** Section 5 shows one fork settling into a slower, differently allocating state. Three forks is a small sample of how many such outcomes exist, so the spread between forks is poorly estimated. I also did not test `-Djmh.blackhole.autoDetect=false`. JMH used experimental compiler blackholes on this JVM, and its own banner warns results can differ with the Blackhole mode.
5. **An uncontrolled machine.** This is a Windows development machine, not an isolated benchmark host. No CPU pinning or affinity was used, and background processes, thermal state and power management were not controlled beyond what the Hardware line records. The three slow iterations in the timing-only run may be such interference, but nothing in that run confirms it.
6. **Short iterations and synthetic inputs.** One-second iterations cannot show slow drift such as thermal throttling or later JIT recompilation. The inputs are one merchant, one currency, one fixed timestamp, and amounts from a seeded generator. Real payments vary more.

## 8. Reproducing, evidence files, and build notes

Reproduce (from the repository root, then the `benchmarks` folder):

```
powershell -ExecutionPolicy Bypass -File run-benchmarks.ps1
```

The script builds and tests the service (Docker must be running, because the service's integration tests use Testcontainers), builds the module, and runs the suite by name (`LedgerBenchmarks`). It writes `benchmarks/results-latest.json`, so the committed `benchmarks/results.json` (the 28 Sep run behind every figure above) is never overwritten by accident. The jar also holds `BrokenBenchmark` and `FixedBenchmark`, which use JMH defaults and would add about 25 minutes, so the scripted run leaves them out.

Evidence files, all in `benchmarks/`:

| File | What it is |
|---|---|
| `results.json` | Final run, full config, `-prof gc` (source of section 1) |
| `machine.txt` | CPU, RAM, OS and power plan, with what was and was not recorded for the 28 Sep run |
| `run-final.txt` | Console log of the final run |
| `results-timing.json`, `run-timing.txt` | Full-config run without the profiler |
| `state-smoke-run.txt` | Reduced-settings run of the `@State` suite |
| `broken-run.txt`, `fixed-run.txt` | Reduced-settings dead code elimination demo |

Build notes (each one silently broke or blocked the build once):
- `benchmarks` has no `<parent>`. This Maven requires a parent to have packaging `pom`, and the root project is the Spring Boot app (packaging `jar`).
- The root project's Spring Boot plugin uses `<classifier>exec</classifier>`. Without it, the repackaged fat jar replaces the plain jar, and its classes sit under `BOOT-INF/classes`, where the benchmarks module cannot see them.
- The compiler plugin sets `<proc>full</proc>` with `annotationProcessorPaths` for `jmh-generator-annprocess`. On JDK 23 and later, annotation processors on the classpath are no longer run by default. Without this the build succeeded but generated no benchmarks; that was caught by checking `target/generated-sources` and `META-INF/BenchmarkList` in the jar.
- Source files were written without a byte-order mark. A BOM made `javac` fail with `illegal character: '\ufeff'`.

## 9. Repeat run on 24 October 2026

The module was rebuilt on a fresh branch from `main` (with `calculateSettlement` extracted from `settlementFor()`) and the suite was run again with the same configuration (3 forks x 5 warmup + 10 measurement iterations, `-prof gc`). The raw output is kept in `benchmarks/results-verification-run.json`. That run used an earlier version of `run-benchmarks.ps1`, which skipped the service's own tests; the script has since been changed to run them. The benchmark code itself was not changed.

| Benchmark | 28 Sep (`results.json`) | 24 Oct (repeat) | Allocated per op | Verdict |
|---|---|---|---|---|
| `mapToResponse` | 0.00427 +/- 0.00005 us, [0.00422, 0.00432] | 0.00444 +/- 0.00007 us, [0.00438, 0.00451] | 40 B in both | **Different**: intervals do not overlap, the repeat was about 4% slower |
| `entityToString` | 0.0836 +/- 0.0008 us, [0.0828, 0.0844] | 0.0874 +/- 0.0009 us, [0.0865, 0.0884] | 256 B in both | **Different**: intervals do not overlap, the repeat was about 5% slower |
| `calculateSettlement` | 0.618 +/- 0.043 us, [0.576, 0.661] | 0.673 +/- 0.046 us, [0.627, 0.719] | 237.3 B in both | **Inconclusive**: intervals overlap |

Allocation per operation reproduced exactly, so the work done per call did not change. The two fast methods were 4 to 5% slower on the second day by more than their own error bars. I did not isolate the cause. Candidates are the machine's power state and thermal condition (the power plan during the 28 Sep run was not recorded), background load, and a different JIT outcome in a new set of JVMs. The practical conclusion is that the error bars in section 1 describe the spread within one run, not the spread between days, so differences of a few percent between separate days should not be read as changes in the code.
