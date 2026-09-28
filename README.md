# ledger-settlement (Spring Boot service)

Persists Ledger payments in PostgreSQL and serves two endpoints: record a payment, and
report what a merchant is owed. Built to replace the in-memory store that lost an
evening of MR-4471's records on a restart on 4 March 2026.

## Stack

- Java 25
- Spring Boot 4.1.1 (`spring-boot-starter-parent`, so every starter's own version comes
  from that one BOM rather than being pinned separately)
- Spring Web, Spring Data JPA, Bean Validation, PostgreSQL driver, Flyway
- Testcontainers (`spring-boot-testcontainers`, `testcontainers-bom:1.20.4`) for the
  integration test

## Layout

```
ledger-settlement-service/
├── pom.xml
├── README.md
├── research.md              documentation citation, Agent Review, honest build status
├── endpoint-log.md           the five required calls, with reproduce-it-yourself curl
├── agent-endpoint.java        unedited AI agent output, audited in research.md
├── src/main/resources/
│   ├── application.yml       ledger.fee-rate: 0.031, ddl-auto: validate
│   └── db/migration/V1__create_payments.sql
└── src/main/java/com/ledger/settlement/
    ├── LedgerApplication.java
    ├── config/LedgerProperties.java
    ├── domain/PaymentEntity.java
    ├── repository/PaymentRepository.java
    ├── service/SettlementService.java
    ├── web/  RecordPaymentRequest, PaymentResponse, SettlementResponse, PaymentController
    └── error/ MerchantNotFoundException, PaymentNotFoundException, ProblemHandler
└── src/test/java/com/ledger/settlement/PaymentControllerIT.java
```

## The settlement rule

`ledger.fee-rate: 0.031` in `application.yml` is converted **once**, at
`SettlementService` construction, from a `BigDecimal` to an exact `long` count of basis
points (310) — never multiplied against money as a `double`. Every calculation after that
point is `long` arithmetic: `fee = gross * 310 / 10_000`. For the lab's reference payment:

```
gross 128450 -> fee 3981 -> net 124469
```

That is the same rounding discipline every earlier lab in this module enforced in plain
Java, now carried through a Spring `@ConfigurationProperties` binding without ever
touching a `double`.

## Why PaymentEntity is a class, not a record

Hibernate instantiates a managed entity through a no-argument constructor and populates
its fields by reflection afterward — there is no constructor call to pass values through,
so a record's single, final, all-args canonical constructor cannot satisfy that protocol.
Full reasoning is in the class's own javadoc.

```
mvn clean verify
```

`PaymentControllerIT` starts a real `postgres:17-alpine` container via Testcontainers,
posts 128,450 minor units GBP for MR-4471, and asserts the settlement comes back at
124,469 minor units — plus four more tests covering negative amounts, a blank
merchantId, an unknown merchant, and the GET /payments/{id} endpoint added for the Agent
Review step.


## Manual smoke test

```bash
docker run -d --name ledger-pg -e POSTGRES_DB=ledger -e POSTGRES_USER=ledger \
  -e POSTGRES_PASSWORD=ledger -p 5432:5432 postgres:17-alpine

export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ledger
export SPRING_DATASOURCE_USERNAME=ledger
export SPRING_DATASOURCE_PASSWORD=ledger
mvn spring-boot:run
```


## JMH Benchmarks

The repository contains a standalone Maven benchmark module under `benchmarks/`.

The benchmark module uses JMH 1.37 and benchmarks real methods from the Ledger settlement service:

* `PaymentResponse.from(PaymentEntity)`
* `PaymentEntity.toString()`
* `SettlementService.calculateSettlement(List<PaymentEntity>)`

The benchmark JAR is built separately from the Spring Boot application so that the application continues to produce its normal executable JAR.

### Run the benchmarks

From the repository root, run the single command:

```powershell
.\run-benchmarks.ps1
```

The script performs the complete benchmark workflow:

1. Builds and installs the Ledger settlement service.
2. Builds the `benchmarks` Maven module.
3. Runs the `LedgerBenchmarks` JMH suite.
4. Enables the JMH GC profiler.
5. Writes the machine-readable results to:

```text
benchmarks/results.json
```

The equivalent benchmark invocation performed by the script is:

```powershell
java -jar target\benchmarks.jar LedgerBenchmarks -prof gc -rf json -rff results.json
```

### Benchmark configuration

`LedgerBenchmarks` uses:

* Java 25
* JMH 1.37
* 3 forks
* 5 warmup iterations per fork
* 10 measurement iterations per fork
* 1 second per warmup iteration
* 1 second per measurement iteration
* Average-time mode
* Microseconds as the output unit
* JMH GC profiler

The benchmark uses fixed, seeded inputs where appropriate so that benchmark setup is repeatable.

### Benchmark module

The benchmark module is located at:

```text
benchmarks/
├── pom.xml
└── src/
    └── main/
        └── java/
```

It is intentionally a standalone Maven project rather than a Maven child inheriting from the Spring Boot application POM.

The benchmark module depends on the normal, non-executable `ledger-settlement` JAR:

```xml
<dependency>
    <groupId>com.ledger</groupId>
    <artifactId>ledger-settlement</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

The Spring Boot Maven plugin uses the `exec` classifier for the executable application JAR. This keeps the normal application artifact available for the benchmark module.

### Benchmark results

The final benchmark output is stored in:

```text
benchmarks/results.json
```

Additional benchmark evidence is stored in the `benchmarks/` directory, including:

```text
benchmarks/
├── results.json
├── run-final.txt
├── results-timing.json
├── run-timing.txt
├── state-smoke-run.txt
├── broken-run.txt
└── fixed-run.txt
```

`results.json` contains the full-precision output from the final benchmark run.

### Dead-code elimination demonstration

The benchmark suite also contains `BrokenBenchmark` and `FixedBenchmark` to demonstrate why benchmark results must consume the result of the operation being measured.

The intentionally broken version discards the result:

```java
@Benchmark
public void mapToResponse_discardsResult() {
    PaymentResponse.from(ENTITY);
}
```

The fixed versions either return the result or consume it using JMH's `Blackhole`.

The broken benchmark produces an unrealistically small result because the JIT compiler can eliminate work whose result is never observed.

These demonstration benchmarks are not included in the normal benchmark command. The one-command workflow runs only:

```text
LedgerBenchmarks
```

so the normal benchmark run remains focused and does not execute the demonstration suite.

```
```

