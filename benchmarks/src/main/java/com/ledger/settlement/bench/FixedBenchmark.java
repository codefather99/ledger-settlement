package com.ledger.settlement.bench;

import com.ledger.settlement.domain.PaymentEntity;
import com.ledger.settlement.web.PaymentResponse;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.infra.Blackhole;

/**
 * The same mapping call as BrokenBenchmark, but the result is now observed, so the JIT
 * cannot delete it. Two fixes shown side by side: return the value, or hand it to a
 * Blackhole. The input is still a static final constant on purpose - moving it into
 * @State is the next step, so this step isolates one change.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class FixedBenchmark {

    private static final PaymentEntity ENTITY = new PaymentEntity(
            "PAY-bench", "MR-4471", 128_450L, "GBP", Instant.now());

    @Benchmark
    public PaymentResponse mapToResponse_returnsResult() {
        return PaymentResponse.from(ENTITY);
    }

    @Benchmark
    public void mapToResponse_blackhole(Blackhole bh) {
        bh.consume(PaymentResponse.from(ENTITY));
    }
}