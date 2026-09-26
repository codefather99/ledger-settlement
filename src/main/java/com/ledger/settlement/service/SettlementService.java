package com.ledger.settlement.service;

import com.ledger.settlement.config.LedgerProperties;
import com.ledger.settlement.domain.PaymentEntity;
import com.ledger.settlement.error.MerchantNotFoundException;
import com.ledger.settlement.error.PaymentNotFoundException;
import com.ledger.settlement.repository.PaymentRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records payments and works out what a merchant is owed.
 *
 * Constructor injection only - PaymentRepository and LedgerProperties arrive as
 * constructor parameters, both final. This is one of the four defects checked for in
 * the Agent Review (see research.md): field injection with @Autowired makes a class
 * impossible to construct in a plain unit test without reflection or a full Spring
 * context, and hides the class's real dependencies from anyone reading its API.
 */
@Service
public class SettlementService {

    private final PaymentRepository paymentRepository;
    private final FeeScheduleProvider feeScheduleProvider;

    /**
     * The fee rate in basis points (one ten-thousandth), converted ONCE from the
     * BigDecimal in application.yml. 0.031 becomes 310. Every calculation after this
     * point is long arithmetic: gross * feeRateBasisPoints / 10_000. This is the same
     * conversion the sjv-l0-1 lab's SettlementCalculator did with a hard-coded 310;
     * here the number comes from configuration, but the arithmetic discipline is
     * identical, and deliberately so.
     */
    private final long feeRateBasisPoints;

    public SettlementService(PaymentRepository paymentRepository, LedgerProperties ledgerProperties,
                             FeeScheduleProvider feeScheduleProvider) {
        this.paymentRepository = paymentRepository;
        this.feeRateBasisPoints = toBasisPoints(ledgerProperties.getFeeRate());
        this.feeScheduleProvider = feeScheduleProvider;
    }

    /**
     * Converts a decimal fee rate (e.g. 0.031) to an exact integer count of basis
     * points (310). Uses BigDecimal.multiply and scale checking rather than a double
     * multiplication, so a configuration value that is not a whole number of basis
     * points fails loudly at startup instead of silently rounding.
     */
    private static long toBasisPoints(BigDecimal feeRate) {
        if (feeRate == null) {
            throw new IllegalStateException("ledger.fee-rate must be set");
        }
        if (feeRate.signum() < 0 || feeRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalStateException(
                    "ledger.fee-rate must be between 0 and 1, was: " + feeRate);
        }
        BigDecimal basisPoints = feeRate.multiply(BigDecimal.valueOf(10_000));
        if (basisPoints.stripTrailingZeros().scale() > 0) {
            throw new IllegalStateException(
                    "ledger.fee-rate must resolve to a whole number of basis points, was: " + feeRate);
        }
        return basisPoints.setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    /**
     * Records a new payment.
     *
     * @Transactional: the insert either fully commits or fully rolls back. There is only
     * one write here today, but the annotation states the boundary explicitly rather than
     * relying on Spring's default proxy-per-repository-call behaviour, so a second write
     * added later (an audit row, an outbox event) joins the same transaction by default
     * instead of silently running outside it.
     */
    @Transactional
    public PaymentEntity record(String merchantId, long amountMinor, String currency) {
        String id = "PAY-" + UUID.randomUUID();
        PaymentEntity entity = new PaymentEntity(id, merchantId, amountMinor, currency, Instant.now());
        return paymentRepository.save(entity);
    }

    /** A single payment by id, or throws if none exists. Backs GET /payments/{id}. */
    @Transactional(readOnly = true)
    public PaymentEntity findById(String paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    /**
     * The net amount owed to a merchant, in minor units, after the configured fee.
     *
     * @throws MerchantNotFoundException if the merchant has no payments recorded
     */
    @Transactional(readOnly = true)
    public long settlementFor(String merchantId) {
        List<PaymentEntity> payments = paymentRepository.findByMerchantId(merchantId);
        if (payments.isEmpty()) {
            throw new MerchantNotFoundException(merchantId);
        }
        long gross = payments.stream().mapToLong(PaymentEntity::getAmountMinor).sum();
        Long overrideBasisPoints = feeScheduleProvider.overrideBasisPointsFor(merchantId);
        long basisPoints = overrideBasisPoints != null ? overrideBasisPoints : feeRateBasisPoints;
        long fee = gross * basisPoints / 10_000;
        return gross - fee;
    }
}
