package com.ledger.settlement.fees;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Per-merchant fee overrides in basis points.
 *
 * FIX (lab SJV-L1): the table is loaded once, in the constructor, when Spring creates this
 * bean on the main platform thread during startup, before the web server accepts traffic.
 * The map is immutable and published through a final field, so request threads only read it.
 * Result semantics are identical to the class-initializer version.
 */
@Component
public final class FeeSchedule {

    private final Map<String, Long> overrides;

    @Autowired
    public FeeSchedule() {
        this(FeeScheduleClient::fetchOverrides);
    }

    FeeSchedule(Supplier<Map<String, Long>> loader) {
        this.overrides = Map.copyOf(loader.get());
    }

    public Optional<Long> overrideBasisPoints(String merchantId) {
        return Optional.ofNullable(overrides.get(merchantId));
    }
}