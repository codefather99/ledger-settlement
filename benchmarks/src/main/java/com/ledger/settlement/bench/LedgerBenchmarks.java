package com.ledger.settlement.bench;

import com.ledger.settlement.config.LedgerProperties;
import com.ledger.settlement.domain.PaymentEntity;
import com.ledger.settlement.service.SettlementService;
import com.ledger.settlement.web.PaymentResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Three real methods from the Ledger settlement service. All inputs live in LedgerState
 * (non-final fields filled in @Setup) so the JIT cannot fold them into the measured
 * code, and every benchmark returns its result so nothing can be eliminated as dead.
 *
 * Why forks matter: each fork is a fresh JVM that makes its own JIT and memory-layout
 * decisions, so one JVM's lucky or unlucky choices show up as run-to-run variance
 * instead of being mistaken for the true cost of the method.
 *
 * Iteration time is set to 1 second (JMH's default is 10 s) to keep the whole suite to
 * a few minutes; these operations run millions of times per iteration, so 1 s is ample.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
public class LedgerBenchmarks {

    @State(Scope.Benchmark)
    public static class LedgerState {
        public PaymentEntity entity;
        public List<PaymentEntity> payments;
        public SettlementService service;

        @Setup
        public void setup() {
            Random random = new Random(42);
            Instant recordedAt = Instant.parse("2026-03-04T21:15:30Z");

            entity = new PaymentEntity(
                    "PAY-" + Long.toHexString(random.nextLong()),
                    "MR-4471", 1_000L + random.nextInt(500_000), "GBP", recordedAt);

            payments = new ArrayList<>(1_000);
            for (int i = 0; i < 1_000; i++) {
                payments.add(new PaymentEntity(
                        "PAY-" + i, "MR-4471", 1_000L + random.nextInt(500_000), "GBP", recordedAt));
            }

            // The repository is never touched by calculateSettlement, so null is safe here.
            service = new SettlementService(null, new LedgerProperties(new BigDecimal("0.031")));
        }
    }

    @Benchmark
    public PaymentResponse mapToResponse(LedgerState state) {
        return PaymentResponse.from(state.entity);
    }

    @Benchmark
    public String entityToString(LedgerState state) {
        return state.entity.toString();
    }

    @Benchmark
    public long calculateSettlement(LedgerState state) {
        return state.service.calculateSettlement(state.payments);
    }
}