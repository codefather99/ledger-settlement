package com.ledger.settlement.bench;

import com.ledger.settlement.domain.PaymentEntity;
import com.ledger.settlement.web.PaymentResponse;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;

/**
 * Deliberately wrong: computes PaymentResponse.from(entity) and discards the result.
 * The JIT is free to prove this method's return value is never observed anywhere and
 * eliminate the call entirely - this benchmark exists to capture that failure mode in
 * writing, per the lab's own instructions, not to measure anything real.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class BrokenBenchmark {

    private static final PaymentEntity ENTITY = new PaymentEntity(
            "PAY-bench", "MR-4471", 128_450L, "GBP", Instant.now());

    @Benchmark
    public void mapToResponse_discardsResult() {
        PaymentResponse.from(ENTITY);
    }
}