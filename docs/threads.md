# Virtual Thread Migration and Pinning Hunt (SJV-L1)

Service: Ledger settlement service (Spring Boot 4.1.1, Java 25, PostgreSQL 17).
Branch: `feature/virtual-threads`, based on `main` at commit `76043f6`.
Every number below comes from the saved raw outputs in `perf/results/` (attached to the pull request).

## 1. Toolchain

```
java version "25.0.1" 2025-10-21 LTS
Java(TM) SE Runtime Environment (build 25.0.1+8-LTS-27)
Java HotSpot(TM) 64-Bit Server VM (build 25.0.1+8-LTS-27, mixed mode, sharing)

Apache Maven 3.9.16
Java version: 25.0.1, vendor: Oracle Corporation, runtime: C:\Program Files\Java\jdk-25
OS name: "windows 11", version: "10.0", arch: "amd64"
```

All measurements used this JDK build, on one Windows 11 machine. k6 v2.3.0, the service, PostgreSQL (Docker container `ledger-db`) and the downstream stub all ran on the same machine.

## 2. Load profile (identical for every run)

`perf/threads-load.js`: `GET /payments/settlement?merchantId=MR-4471`, 200 virtual users, 60 s steady state, preceded by a 5 s ramp-up. Ramp requests are tagged `phase=ramp` and excluded from every figure; only `phase=steady` is reported. The database held 100 payments for `MR-4471` (settlement answer `124517`, checked before and after each configuration).

Throughput is steady-phase requests divided by 60 s (k6's printed rate also divides by the ramp, so it is not used).

Why a ramp: the first attempt (no ramp) produced 0.06% to 0.09% failures, all `connectex: ... actively refused it`. With 200 users connecting in the same instant, Tomcat's accept queue (`accept-count`, default 100) overflows and Windows refuses the extra connections. Those runs are kept in `perf/results/discarded-no-ramp/` and are in no table. All reported runs have 0 failed requests and 0 refused connections.

## 3. Current pooling (before the migration)

There was no application executor bean and no pool configuration in the service (searched `src/` for `Executor`, `ThreadPool`, `@Async`, `virtual`, `tomcat`, `threads`: no matches). The defaults of Spring Boot 4.1.1 therefore applied:

| Pool | Setting | Value |
|---|---|---|
| Tomcat request threads | `server.tomcat.threads.max` | 200 |
| Tomcat request threads | `server.tomcat.threads.min-spare` | 10 |
| Tomcat accept queue | `server.tomcat.accept-count` | 100 |
| Database connections (Hikari) | `maximum-pool-size` | 10 |

How the pool bounded the baseline (thread dump taken 25 s into a 45 s, 200-user run, `perf/results/baseline-threaddump.txt`):

| Measurement | Result |
|---|---|
| Tomcat request threads (`http-nio-8080-exec-*`) | 200 |
| Threads waiting in `ConcurrentBag.borrow` (Hikari connection wait) | 187 |
| Open database connections (`pg_stat_activity`) | 10 |

200 request threads competed for 10 database connections, and 187 of them were queued. The 10-connection pool, not the 200-thread Tomcat pool, bounded throughput.

## 4. Migration

- `src/main/resources/application.yml` renamed to `application.yaml`, with `spring.threads.virtual.enabled: true`.
- New `ExecutorConfig`: bean `applicationTaskExecutor` built from `Executors.newVirtualThreadPerTaskExecutor()`. No pooled application executor existed, so none was removed.
- `VirtualThreadsIT` proves the request path really is virtual: an HTTP request handler reports `Thread.currentThread().isVirtual() == true`, and so does a task run on the application executor.

## 5. Baseline vs migrated (warm JVM, no recording running)

Each configuration: one discarded warm-up run, then three measured runs.

| Run | Steady requests | Throughput (req/s) | p50 | p95 | p99 | Failed |
|---|---|---|---|---|---|---|
| Baseline 1 | 171,604 | 2,860.1 | 69.61 ms | 77.51 ms | 137.15 ms | 0 |
| Baseline 2 | 171,208 | 2,853.5 | 69.79 ms | 77.73 ms | 137.31 ms | 0 |
| Baseline 3 | 167,444 | 2,790.7 | 71.26 ms | 79.92 ms | 141.04 ms | 0 |
| **Baseline median** | | **2,853.5** | **69.79 ms** | **77.73 ms** | **137.31 ms** | |
| Migrated 1 | 163,282 | 2,721.4 | 73.18 ms | 81.22 ms | 143.40 ms | 0 |
| Migrated 2 | 158,910 | 2,648.5 | 74.52 ms | 89.42 ms | 147.91 ms | 0 |
| Migrated 3 | 162,090 | 2,701.5 | 73.40 ms | 86.24 ms | 144.24 ms | 0 |
| **Migrated median** | | **2,701.5** | **73.40 ms** | **86.24 ms** | **144.24 ms** | |
| **Change** | | **-5.3%** | **+5.2%** | **+10.9%** | **+5.0%** | |

**Verdict: worse.** Throughput fell about 5%. The run-to-run spread is about 2.5% within each configuration, and every migrated run is below every baseline run. (No explanation is given here, as the lab instructs.)

## 6. Planted defect

The service had no downstream client, so one was added so the defect is a genuine blocking network call rather than a sleep:

- `perf/FeeScheduleStub.java`: a small HTTP service on port 9090 whose `/fees` endpoint takes 120 ms to answer, like a remote system.
- `FeeScheduleClient.fetchOverrides()`: a blocking `HttpClient.send` call to it.
- `FeeSchedule`: `static { OVERRIDES = FeeScheduleClient.fetchOverrides(); }`, a class initializer, reached on the first `settlementFor` call. `SettlementService` uses it for optional per-merchant fee overrides (none apply to `MR-4471`, so its result is unchanged).

The service was restarted cold before every run from here on (the initializer runs once per JVM). `perf/cold-run.ps1` does this: fresh JVM, Flight Recorder attached before any request, load, stop recording.

## 7. Pinning proved with Flight Recorder

Command (lab form, run against the freshly started service before any request):

```
jcmd 56616 JFR.start name=pin settings=profile filename=pinned.jfr
k6 run perf/threads-load.js
jcmd 56616 JFR.stop name=pin
jfr print --events jdk.VirtualThreadPinned --stack-depth 64 pinned.jfr
```

(`jdk.tracePinnedThreads` was removed in Java 24; `jdk.VirtualThreadPinned` is its replacement. The event is recorded only for pins of 20 ms or longer by default.)

`pinned.jfr` contains **48** `jdk.VirtualThreadPinned` events. Ten involve `FeeSchedule`: one holder (`VM call to ...FeeSchedule.<clinit> on stack`) and nine requests that waited (`Waited for initialization of com.ledger.settlement.fees.FeeSchedule by another thread`).

The holder event (stack shortened by `...`; the full text is in `perf/results/pinned-events-full.txt`):

```
jdk.VirtualThreadPinned {
  startTime = 12:05:45.765 (2026-10-21)
  duration = 137 ms
  blockingOperation = "LockSupport.park"
  pinnedReason = "VM call to com.ledger.settlement.fees.FeeSchedule.<clinit> on stack"
  carrierThread = "ForkJoinPool-1-worker-9" (javaThreadId = 110)
  eventThread = "tomcat-handler-8" (javaThreadId = 109, virtual)
  stackTrace = [
    java.lang.VirtualThread.parkOnCarrierThread(boolean, long) line: 826
    java.lang.VirtualThread.park() line: 751
    java.lang.System$1.parkVirtualThread() line: 2284
    java.util.concurrent.locks.LockSupport.park(Object) line: 221
    java.util.concurrent.CompletableFuture$Signaller.block() line: 1885
    java.util.concurrent.ForkJoinPool.unmanagedBlock(ForkJoinPool$ManagedBlocker) line: 4364
    java.util.concurrent.ForkJoinPool.managedBlock(ForkJoinPool$ManagedBlocker) line: 4310
    java.util.concurrent.CompletableFuture.waitingGet(boolean) line: 1919
    java.util.concurrent.CompletableFuture.get() line: 2093
    jdk.internal.net.http.HttpClientImpl.send(HttpRequest, HttpResponse$BodyHandler) line: 902
    jdk.internal.net.http.HttpClientFacade.send(HttpRequest, HttpResponse$BodyHandler) line: 133
    com.ledger.settlement.fees.FeeScheduleClient.fetchOverrides() line: 31
    com.ledger.settlement.fees.FeeSchedule.<clinit>() line: 19
    com.ledger.settlement.service.SettlementService.settlementFor(String) line: 106
    ...
    com.ledger.settlement.web.PaymentController.settlement(String) line: 67
    ...
  ]
}
```

- **Frame that cannot unmount:** `com.ledger.settlement.fees.FeeSchedule.<clinit>()`, the class initializer, at line 19.
- **Call that blocks inside it:** `FeeScheduleClient.fetchOverrides()` (line 31), which calls `HttpClient.send`, which waits in `CompletableFuture.get()` and parks (`LockSupport.park`) for the roughly 120 ms downstream response. The carrier thread stays held for the whole 137 ms.

## 8. Why synchronized advice does not apply here

Before Java 24, a virtual thread that blocked inside a `synchronized` block or method stayed pinned to its carrier, so advice written then told you to replace `synchronized` with a `ReentrantLock`, which virtual threads can wait on without pinning. JEP 491 (Java 24) removed that restriction, and in any case this blocking call is inside a class initializer, which the JVM runs in a VM call under its own class-initialization lock; there is no `synchronized` block of ours to replace, so a `ReentrantLock` would change nothing.

## 9. Second finding: Hibernate also pins on a cold start

The other 38 events in `pinned.jfr` are not from our code. All are in Hibernate's HQL query parser, initialised the first time the derived query `findByMerchantId` runs on a request's virtual thread: 30 events with reason `Freeze or preempt failed (2)` and 8 with `Waited for initialization of org.hibernate.grammars.hql.HqlLexer by another thread`. This happens on every cold start, with or without the planted defect, so fixing `FeeSchedule` alone would not have produced an empty recording.

## 10. Fix

1. `FeeSchedule` is now a Spring bean. The table is loaded once in its constructor, on the main platform thread during startup, before the web server accepts requests. The map is immutable and held in a `final` field. Results are identical for every merchant.
2. `QueryWarmup` runs the settlement query once on the main thread during startup (`SmartInitializingSingleton`), so Hibernate's first-query class initialisation happens before traffic. No request traffic is used to warm the service, and every run is still a cold JVM start.
3. Test `FeeScheduleConcurrencyTest`: 64 virtual threads are released at the same moment on a `CountDownLatch`; every one sees the complete table (overrides present, other merchants empty), and the loader ran exactly once.

`mvn verify` passes: 8 integration tests (`PaymentControllerIT` 6, `VirtualThreadsIT` 2) plus the unit test.

## 11. Before and after (cold start, Flight Recorder attached, same load)

| Cold-start run | Steady requests | Throughput (req/s) | p50 | p95 | p99 | Pinned events |
|---|---|---|---|---|---|---|
| Defect, `pinned.jfr` run | 150,991 | 2,516.5 | 78.30 ms | 95.71 ms | 156.89 ms | 48 |
| Defect, repeat 1 | 154,294 | 2,571.6 | 76.47 ms | 100.17 ms | 154.75 ms | 19 |
| Defect, repeat 2 | 144,180 | 2,403.0 | 78.91 ms | 117.02 ms | 163.67 ms | 50 |
| Defect, repeat 3 | 151,573 | 2,526.2 | 76.85 ms | 108.54 ms | 157.04 ms | 64 |
| **Defect median (n=4)** | | **2,521.4** | **77.58 ms** | **104.36 ms** | **156.97 ms** | **48 to 64 (range 19 to 64)** |
| Fixed, `fixed.jfr` run | 148,819 | 2,480.3 | 79.25 ms | 95.94 ms | 158.11 ms | 0 |
| Fixed, repeat 2 | 158,314 | 2,638.6 | 75.37 ms | 83.21 ms | 147.83 ms | 0 |
| Fixed, repeat 3 | 157,227 | 2,620.5 | 75.81 ms | 84.70 ms | 148.69 ms | 0 |
| **Fixed median (n=3)** | | **2,620.5** | **75.81 ms** | **84.70 ms** | **148.69 ms** | **0** |
| **Fixed vs defect (medians)** | | **+3.9%** | **-2.3%** | **-18.8%** | **-5.3%** | |

`fixed.jfr` contains no `jdk.VirtualThreadPinned` events (`jfr print --events jdk.VirtualThreadPinned fixed.jfr` prints nothing), and neither do the two repeat recordings. Both recordings were made with identical commands and settings (`settings=profile`), and `pinned.jfr` shows the event is captured under those settings. The accurate claim is "no pins of 20 ms or longer".

Reading the table honestly: the throughput ranges overlap (defect 2,403 to 2,572, fixed 2,480 to 2,639), so the throughput gain is suggestive, not proven. The p95 improvement is more consistent (fixed 83.2 to 95.9 ms, defect 95.7 to 117.0 ms). The evidence for the fix is the recording, not the percentiles: the defect stalls only the requests that arrive while the table loads, a few hundred milliseconds out of a 60 s run. Cold-start run 1 of the fixed build was about 6% below its two repeats; the cause was not identified.

The cold runs are lower than the warm runs in section 5 because they are cold and have Flight Recorder running. Sections 5 and 11 should not be compared with each other.

## 12. Limits

- Load generator, service, database and stub share one machine, so they compete for CPU.
- The downstream is a stub with a fixed 120 ms delay, not a real service.
- Three runs per configuration (four for the defect); this shows the size of the spread but is not a statistical test.
- The migrated build was measured before the downstream client and the warm-up existed. Section 5 therefore compares only the migration; sections 6 to 11 compare only the defect and the fix.

## 13. Reproduce

```
docker run -d --name ledger-db -e POSTGRES_DB=ledger -e POSTGRES_USER=ledger -e POSTGRES_PASSWORD=ledger -p 5434:5432 postgres:17
mvn clean verify
java perf/FeeScheduleStub.java
powershell -ExecutionPolicy Bypass -File perf/cold-run.ps1 -Jar target/ledger-settlement-1.0.0-SNAPSHOT.jar -Label fixed-run
```

Seed 100 payments of 1,285 minor units for `MR-4471` first; the expected settlement is `124517`.
