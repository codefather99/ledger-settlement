# Virtual Thread Migration and Pinning Hunt — ledger-settlement

## Toolchain

```
$ java -version
java version "25.0.1" 2025-10-21 LTS
Java(TM) SE Runtime Environment (build 25.0.1+8-LTS-27)
Java HotSpot(TM) 64-Bit Server VM (build 25.0.1+8-LTS-27, mixed mode, sharing)

$ mvn -v
Apache Maven 3.9.11 
Maven home: C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1\plugins\maven\lib\maven3
Java version: 25.0.1, vendor: Oracle Corporation, runtime: C:\Program Files\Java\jdk-25
Default locale: en_US, platform encoding: IBM437
OS name: "windows 11", version: "10.0", arch: "amd64", family: "windows"
```

Branch: `feature/virtual-threads`, off `main` at commit `e927fa2`.


# Virtual Thread Migration and Pinning Hunt — `ledger-settlement`

## 1. Objective

This lab investigates the migration of the `ledger-settlement` service to Java virtual threads and identifies a deliberate virtual-thread pinning defect.

The objectives were to:

1. Run the settlement service using the normal platform-thread executor configuration.
2. Run the service using Java virtual threads.
3. Identify a blocking operation that pins a virtual thread to its carrier thread.
4. Capture the pinning behaviour using Java Flight Recorder (JFR).
5. Fix the defect by moving blocking initialization out of the request path.
6. Re-run the workload and verify that the application-specific pinning events are gone.
7. Compare the observed performance and error rates.

---

## 2. Environment

The work was performed on:

* Operating System: Windows 11
* Java: 25.0.1 LTS
* Maven: 3.9.11
* Spring Boot: 4.1.1
* IDE: IntelliJ IDEA 2025.3
* Docker Desktop: 29.7.2
* Git branch: `feature/virtual-threads`
* Base branch: `main`
* Starting commit: `e927fa2`

Project:

```text
ledger-settlement
```

Main package:

```text
com.ledger.settlement
```

---

## 3. Virtual Thread Configuration

The Spring Boot application was configured to enable virtual threads:

```yaml
server:
  port: 8080

spring:
  application:
    name: ledger-settlement
  threads:
    virtual:
      enabled: true
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
  flyway:
    enabled: true
    locations: classpath:db/migration

ledger:
  fee-rate: 0.031
```

The important configuration is:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

This allows Spring Boot's embedded Tomcat server to use virtual threads for request processing.

---

## 4. Application Functionality

The service exposes the following endpoints:

```text
POST /payments
GET  /payments/{id}
GET  /payments/settlement?merchantId=...
```

The virtual-thread investigation focused on:

```text
GET /payments/settlement?merchantId=...
```

The workload used 200 concurrent clients for 60 seconds.

---

# 5. Original Pinning Defect

The deliberate defect involved lazy static class initialization.

The defective implementation used a static initializer similar to:

```java
static class FeeSchedule {
    static final Map<String, Long> OVERRIDES;

    static {
        OVERRIDES = FeeScheduleClient.fetchOverridesBlocking();
    }

    static Long overrideFor(String merchantId) {
        return OVERRIDES.get(merchantId);
    }
}
```

The fee schedule client deliberately performs a blocking operation:

```java
static Map<String, Long> fetchOverridesBlocking() {
    try {
        Thread.sleep(120);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }

    return Map.of("MR-VIP-1", 150L);
}
```

The important problem is that `FeeSchedule.<clinit>` performs the blocking `Thread.sleep(120)` while a virtual thread is involved in class initialization.

Because class initialization is synchronized by the JVM, other virtual threads attempting to use the class can also wait for the initialization to finish.

This creates virtual-thread pinning.

---

# 6. Standalone Harness

A standalone Java HTTP harness was used for the controlled pinning experiment.

The harness was necessary because the original lab environment could not reliably use the intended external load-testing setup.

The harness was compiled with:

```powershell
javac -d ".\target\harness-classes" ".\harness\LedgerServer.java" ".\harness\LoadGenerator.java"
```

The harness supports:

```text
pooled
virtual
```

execution modes and can enable or disable the deliberate defect.

The virtual-thread mode uses:

```java
Executors.newVirtualThreadPerTaskExecutor()
```

The pooled mode uses a bounded platform-thread pool representing the normal Tomcat-style configuration:

```text
minSpareThreads = 10
maxThreads     = 200
acceptCount     = 100
```

---

# 7. Port Configuration

The real Spring Boot application was already running on:

```text
localhost:8080
```

Therefore, the standalone harness was run on:

```text
localhost:8081
```

This avoided a port conflict with the actual Spring Boot service.

The harness was started in virtual-thread mode with the defect enabled using:

```powershell
java -cp ".\target\harness-classes" LedgerServer 8081 virtual defect-on
```

The listening process was identified using:

```powershell
Get-NetTCPConnection -LocalPort 8081 -State Listen |
    Select-Object LocalAddress,LocalPort,OwningProcess
```

The PID returned by `OwningProcess` was then used for JFR.

---

# 8. Defective Virtual-Thread Test

The defective harness was tested with:

```powershell
java -cp ".\target\harness-classes" LoadGenerator "http://localhost:8081" 200 60
```

The load profile was:

```text
Concurrency = 200
Duration    = 60 seconds
Endpoint    = /payments/settlement
```

The observed result was:

```text
=== Load test result ===
target           = http://localhost:8081/payments/settlement
concurrency      = 200
duration_s       = 60.0
completed        = 1970369
errors           = 47
throughput_rps   = 32835.9
p50_ms           = 5.60
p95_ms           = 11.41
p99_ms           = 13.93
```

### Defective test summary

| Metric      |         Result |
| ----------- | -------------: |
| Concurrency |            200 |
| Duration    |           60 s |
| Completed   |      1,970,369 |
| Errors      |             47 |
| Throughput  | 32,835.9 req/s |
| p50         |        5.60 ms |
| p95         |       11.41 ms |
| p99         |       13.93 ms |

---

# 9. JFR Capture of the Defect

JFR was started against the running defective harness:

```powershell
& "C:\Program Files\Java\jdk-25\bin\jcmd.exe" <PID> JFR.start name=pinning settings=profile duration=70s filename=target/defect.jfr
```

The resulting recording was:

```text
target/defect.jfr
```

The relevant JFR events were inspected with:

```powershell
& "C:\Program Files\Java\jdk-25\bin\jfr.exe" print --events jdk.VirtualThreadPinned ".\target\defect.jfr"
```

JFR reported application-specific virtual-thread pinning.

A representative event was:

```text
jdk.VirtualThreadPinned {
  duration = 119 ms
  blockingOperation = "LockSupport.park"
  pinnedReason = "VM call to LedgerServer$FeeSchedule.<clinit> on stack"
  carrierThread = "ForkJoinPool-1-worker-23"
  eventThread = virtual thread
}
```

The stack trace included:

```text
java.lang.VirtualThread.parkOnCarrierThread(...)
java.lang.VirtualThread.parkNanos(...)
java.lang.VirtualThread.sleepNanos(...)
java.lang.Thread.sleepNanos(...)
java.lang.Thread.sleep(...)
```

This directly connects the pinning event to the blocking sleep performed during `FeeSchedule` class initialization.

Additional events showed other virtual threads waiting for the same class initialization:

```text
pinnedReason =
"Waited for initialization of LedgerServer$FeeSchedule by another thread"
```

The stack included:

```text
LedgerServer.overrideFor(...)
LedgerServer.settle(...)
LedgerServer.lambda$main$0(...)
```

Therefore, the JFR recording provided direct evidence that the lazy static initialization was causing virtual-thread pinning.

## 9.1 Frame That Could Not Unmount

A virtual thread normally works by mounting onto a carrier thread when it needs to execute and unmounting from that carrier when it performs a blocking operation.

In the defective implementation, the virtual thread could not unmount from its carrier because it was executing JVM class initialization code:

```text
LedgerServer$FeeSchedule.<clinit>
```

The JFR event identified the pinned reason as:

```text
VM call to LedgerServer$FeeSchedule.<clinit> on stack
```

The relevant call path was:

```text
FeeSchedule.<clinit>
        |
        v
FeeScheduleClient.fetchOverridesBlocking()
        |
        v
Thread.sleep(120)
        |
        v
VirtualThread.parkOnCarrierThread()
```

The important point is that the blocking operation occurred while the virtual thread was inside the class initializer (`<clinit>`).

Because the class initializer is protected by JVM class-initialization semantics, the virtual thread could not simply unmount from its carrier while waiting. The carrier therefore remained occupied by the pinned virtual thread.

Other virtual threads attempting to initialize or use the same class were also observed in JFR with:

```text
Waited for initialization of LedgerServer$FeeSchedule by another thread
```

This explains why multiple pinning events appeared in the defective recording.

In simplified form:

```text
Normal virtual-thread blocking:

Virtual Thread
      |
      v
Blocking operation
      |
      v
Unmount from carrier
      |
      v
Carrier available for another virtual thread


Defective class initialization:

Virtual Thread
      |
      v
FeeSchedule.<clinit>
      |
      v
Thread.sleep(120)
      |
      X
Cannot unmount
      |
      v
Carrier remains occupied
```

The JFR `jdk.VirtualThreadPinned` event therefore provides runtime evidence that the class initializer was the frame preventing normal virtual-thread unmounting.

## 9.2 Note on `synchronized` and Java Version

Older virtual-thread guidance, particularly advice written before Java 24, commonly recommended avoiding `synchronized` blocks and methods around blocking operations because they could pin a virtual thread to its carrier thread.

That advice should not be applied uncritically to this Java 25 implementation.

Starting with Java 24, the JDK's virtual-thread implementation was improved so that virtual threads can generally block while holding monitors without the same monitor-related pinning behaviour that was a concern in earlier JDK releases.

This lab uses:

```text
Java 25.0.1 LTS
```

Therefore, the defect investigated here is **not a generic "`synchronized` is bad for virtual threads" problem**.

The actual defect is the blocking operation occurring during JVM class initialization:

```text
FeeSchedule.<clinit>
        |
        v
fetchOverridesBlocking()
        |
        v
Thread.sleep(120)
```

JFR explicitly identified the relevant pinned reason as:

```text
VM call to LedgerServer$FeeSchedule.<clinit> on stack
```

rather than identifying a normal application `synchronized` block as the cause.

The lesson for this Java 25 application is therefore to investigate the actual JFR `jdk.VirtualThreadPinned` event and its stack trace instead of automatically assuming that every use of `synchronized` causes virtual-thread pinning.

### Version-specific takeaway

```text
Pre-Java 24 guidance:
    Be particularly cautious with synchronized + blocking operations.

Java 24+:
    Monitor-related virtual-thread pinning behaviour was improved.

This Java 25 lab:
    The demonstrated pinning defect is caused by blocking
    during class initialization (<clinit>), not by ordinary
    synchronized application code.
```

---

# 10. Interpretation of the Defect

The sequence was:

```text
Virtual thread receives request
        |
        v
FeeSchedule.overrideFor(...)
        |
        v
FeeSchedule class must be initialized
        |
        v
FeeSchedule.<clinit>
        |
        v
fetchOverridesBlocking()
        |
        v
Thread.sleep(120)
        |
        v
Virtual thread becomes pinned
        |
        v
Other virtual threads wait for class initialization
```

The key issue is not simply that `Thread.sleep()` exists.

`Thread.sleep()` is normally compatible with virtual threads because a virtual thread can park and release its carrier.

The problem here is that the sleep occurs while executing a JVM class-initialization path (`<clinit>`). That causes the virtual thread to remain pinned to its carrier.

---

# 11. Fix

The fix was to remove the fee schedule loading from lazy static class initialization.

The application now uses a Spring-managed `FeeScheduleProvider`:

```java
@Component
public class FeeScheduleProvider {

    private final Map<String, Long> overrides;

    public FeeScheduleProvider(FeeScheduleClient feeScheduleClient) {
        this.overrides = feeScheduleClient.fetchOverridesBlocking();
    }

    public Long overrideBasisPointsFor(String merchantId) {
        return overrides.get(merchantId);
    }
}
```

The blocking operation remains:

```java
Thread.sleep(120);
```

but it now occurs when Spring constructs the singleton provider during application startup/context initialization rather than when a virtual-thread request triggers lazy class initialization.

The settlement service receives the provider through constructor injection.

This changes the execution path from:

```text
request
  -> lazy class initialization
  -> blocking initialization
```

to:

```text
application startup
  -> FeeScheduleProvider construction
  -> fee schedule loaded once

request
  -> FeeScheduleProvider lookup
  -> no blocking initialization
```

---

# 12. Fixed Harness Test

After applying the fix, the standalone harness was run with the defect disabled.

Before starting the final JFR recording, the application was warmed up with 100 requests:

```powershell
1..100 | ForEach-Object {
    Invoke-WebRequest "http://localhost:8081/payments/settlement?merchantId=m-0" -UseBasicParsing | Out-Null
}
```

The purpose of the warm-up was to ensure that one-time JDK initialization, such as CLDR locale initialization, happened before JFR started.

The same 200-concurrent, 60-second workload was then executed:

```powershell
java -cp ".\target\harness-classes" LoadGenerator "http://localhost:8081" 200 60
```

The final fixed result was:

```text
=== Load test result ===
target           = http://localhost:8081/payments/settlement
concurrency      = 200
duration_s       = 60.0
completed        = 2004316
errors           = 0
throughput_rps   = 33401.6
p50_ms           = 5.47
p95_ms           = 11.35
p99_ms           = 14.26
```

### Fixed test summary

| Metric      |             Result |
| ----------- | -----------------: |
| Concurrency |                200 |
| Duration    |               60 s |
| Completed   |          2,004,316 |
| Errors      |              **0** |
| Throughput  | **33,401.6 req/s** |
| p50         |        **5.47 ms** |
| p95         |       **11.35 ms** |
| p99         |       **14.26 ms** |

---

# 13. JFR Verification After the Fix

The fixed recording was saved as:

```text
target/fixed.jfr
```

It was inspected with:

```powershell
& "C:\Program Files\Java\jdk-25\bin\jfr.exe" print --events jdk.VirtualThreadPinned ".\target\fixed.jfr"
```

The command produced no output.

A targeted search was also performed:

```powershell
& "C:\Program Files\Java\jdk-25\bin\jfr.exe" print --events jdk.VirtualThreadPinned ".\target\fixed.jfr" |
    Select-String -Pattern "LedgerServer|FeeSchedule"
```

This also produced no output.

Therefore:

```text
LedgerServer/FeeSchedule virtual-thread pinning = 0
```

No `FeeSchedule.<clinit>` pinning events were present in the final fixed recording.
## Before and After Performance Comparison

The same load profile was used for the defective and fixed virtual-thread runs:

* 200 concurrent clients
* 60-second duration
* `GET /payments/settlement`
* Standalone Java harness
* Virtual-thread executor

| Metric              | Before — Defect Enabled | After — Defect Fixed |
| ------------------- | ----------------------: | -------------------: |
| Concurrency         |                     200 |                  200 |
| Duration            |                    60 s |                 60 s |
| Throughput          |      **32,835.9 req/s** |   **33,401.6 req/s** |
| p50                 |             **5.60 ms** |          **5.47 ms** |
| p95                 |            **11.41 ms** |         **11.35 ms** |
| p99                 |            **13.93 ms** |         **14.26 ms** |
| Completed           |               1,970,369 |        **2,004,316** |
| Errors              |                      47 |                **0** |
| FeeSchedule pinning |            **Detected** |     **Not detected** |

### Performance interpretation

The throughput and latency values are relatively close between the two runs. This is expected because the planted defect occurs during lazy class initialization rather than on every settlement request.

The most significant differences are:

1. The defective run produced 47 errors.
2. The fixed run produced 0 errors.
3. The defective JFR recording contained `FeeSchedule.<clinit>` pinning events.
4. The fixed JFR recording contained no `LedgerServer` or `FeeSchedule` pinning events.

The performance table should therefore be interpreted together with the JFR evidence rather than used alone to determine whether the defect was fixed.

The controlled experiment demonstrates:

```text
Before:
32,835.9 req/s
47 errors
FeeSchedule pinning detected

After:
33,401.6 req/s
0 errors
FeeSchedule pinning not detected
```

The p99 latency changed slightly from 13.93 ms to 14.26 ms. This small variation does not contradict the fix; the objective of the experiment is to remove the application-specific virtual-thread pinning, which was verified through JFR.


---

# 14. Earlier Fixed JFR Observation

An earlier fixed recording produced three `jdk.VirtualThreadPinned` events.

Those events were not caused by the application defect. They were related to one-time JDK CLDR locale provider initialization:

```text
pinnedReason =
"Waited for initialization of sun.util.cldr.CLDRLocaleProviderAdapter by another thread"
```

The events lasted approximately:

```text
24.1 ms
26.2 ms
32.4 ms
```

They were unrelated to:

```text
LedgerServer
FeeSchedule
FeeSchedule.<clinit>
```

The final test was therefore warmed up before JFR recording. After warm-up, the fixed recording contained no `jdk.VirtualThreadPinned` output and no application-specific `LedgerServer`/`FeeSchedule` events.

This distinction is important because `jdk.VirtualThreadPinned` is a general JVM event. Its presence alone does not automatically mean that the application contains the defect being investigated.

---

# 15. Defect vs Fixed Results

| Metric                | Defective Virtual Threads | Fixed Virtual Threads |
| --------------------- | ------------------------: | --------------------: |
| Concurrency           |                       200 |                   200 |
| Duration              |                      60 s |                  60 s |
| Completed             |                 1,970,369 |         **2,004,316** |
| Errors                |                        47 |                 **0** |
| Throughput            |            32,835.9 req/s |    **33,401.6 req/s** |
| p50                   |                   5.60 ms |           **5.47 ms** |
| p95                   |                  11.41 ms |          **11.35 ms** |
| p99                   |                  13.93 ms |              14.26 ms |
| `FeeSchedule` pinning |               **Present** |     **None observed** |

The performance numbers are very close because the pinning defect is associated with class initialization and therefore is primarily a cold-start/initialization problem rather than a blocking operation executed on every request.

The most important evidence is therefore the JFR difference:

```text
DEFECT:
LedgerServer$FeeSchedule.<clinit>
        ↓
Thread.sleep(120 ms)
        ↓
jdk.VirtualThreadPinned
```

versus:

```text
FIX:
FeeScheduleProvider initialized during application startup
        ↓
No lazy FeeSchedule class initialization in requests
        ↓
No LedgerServer/FeeSchedule pinning events
```

---

# 16. Real Spring Boot Service Verification

The actual Spring Boot service was also tested separately from the standalone harness.

The real service was running on:

```text
http://localhost:8080
```

The application successfully handled manual API requests, including:

```text
POST /payments
GET /payments/settlement?merchantId=MR-4471
```

A JFR recording was taken against the actual Spring Boot process.

The real Spring service recording contained:

```text
jdk.VirtualThreadPinned = 0
```

This confirms that the Spring application after the code change did not exhibit the planted fee-schedule pinning defect during the recorded workload.

---

# 17. Real Spring Boot Load Test

The actual Spring Boot service was tested using the same general workload:

```text
Concurrency = 200
Duration    = 60 seconds
```

The final observed result was:

```text
completed        = 112545
errors           = 0
throughput_rps   = 1874.0
p50_ms           = 99.89
p95_ms           = 168.79
p99_ms           = 228.38
```

### Real Spring Boot result

| Metric                  |            Result |
| ----------------------- | ----------------: |
| Concurrency             |               200 |
| Duration                |              60 s |
| Completed               |           112,545 |
| Errors                  |             **0** |
| Throughput              | **1,874.0 req/s** |
| p50                     |      **99.89 ms** |
| p95                     |     **168.79 ms** |
| p99                     |     **228.38 ms** |
| JFR application pinning |             **0** |

A previous real-service JFR run also produced zero virtual-thread pinning events, although JFR instrumentation reduced throughput during that particular run:

```text
completed        = 92459
errors           = 0
throughput_rps   = 1536.0
p50_ms           = 116.06
p95_ms           = 223.76
p99_ms           = 311.22
```

The difference demonstrates why throughput measurements taken with JFR enabled should not be treated as directly equivalent to measurements taken without JFR.

---

# 18. Build and Test Status

The Maven project was successfully built and verified during the development process.

The project uses:

```text
Spring Boot 4.1.1
Java 25
Maven
PostgreSQL
Flyway
Testcontainers
```

The application compiled successfully, and the Spring Boot artifact was packaged successfully.

The integration-test environment also successfully connected to Docker Desktop during the later test runs.

---

# 19. Files and Evidence

The main evidence generated during this investigation includes:

```text
docs/threads.md
harness/LedgerServer.java
harness/LoadGenerator.java
target/defect.jfr
target/fixed.jfr
```

The JFR recordings are particularly important because they provide runtime evidence rather than relying only on source-code inspection.

---

# 20. Conclusion

The virtual-thread migration was successfully implemented and the planted pinning defect was reproduced and investigated.

The defect was caused by blocking work inside lazy static class initialization:

```text
FeeSchedule.<clinit>
        ↓
fetchOverridesBlocking()
        ↓
Thread.sleep(120)
        ↓
virtual-thread pinning
```

JFR directly captured the defect with:

```text
pinnedReason =
"VM call to LedgerServer$FeeSchedule.<clinit> on stack"
```

and additional events showing virtual threads waiting for `FeeSchedule` initialization.

The defect was fixed by moving fee-schedule initialization into the Spring-managed `FeeScheduleProvider` constructor:

```java
public FeeScheduleProvider(FeeScheduleClient feeScheduleClient) {
    this.overrides = feeScheduleClient.fetchOverridesBlocking();
}
```

The final fixed workload completed:

```text
2,004,316 requests
0 errors
33,401.6 requests/second
```

The final JFR verification produced no `LedgerServer` or `FeeSchedule` virtual-thread pinning events.

The actual Spring Boot service was also verified under load with:

```text
112,545 completed
0 errors
1,874.0 requests/second
```

and its JFR recording showed zero application virtual-thread pinning events.

Overall, the experiment demonstrates that Java virtual threads can handle blocking operations efficiently when those operations occur in virtual-thread-friendly contexts, but JVM synchronization mechanisms such as class initialization can cause carrier-thread pinning. Moving blocking initialization out of the request-time class-initialization path removes the application-specific pinning defect.
