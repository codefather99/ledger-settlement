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


## Benchmarks

One command, from the repository root (JDK 25, Maven, Docker running):

    powershell -ExecutionPolicy Bypass -File run-benchmarks.ps1

It builds and tests the service, builds the JMH module in `benchmarks/` (a shaded `benchmarks.jar`), and runs `LedgerBenchmarks` with `-prof gc`. Results go to `benchmarks/results-latest.json`. `benchmarks/results.json` is the committed evidence behind `docs/benchmarks.md`.

A full run takes about 3 minutes, measured with the service's 8 integration tests included.
