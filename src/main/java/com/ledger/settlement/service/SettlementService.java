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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final PaymentRepository paymentRepository;
    private final long feeRateBasisPoints;

    public SettlementService(PaymentRepository paymentRepository, LedgerProperties ledgerProperties) {
        this.paymentRepository = paymentRepository;
        this.feeRateBasisPoints = toBasisPoints(ledgerProperties.getFeeRate());
    }

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

    @Transactional
    public PaymentEntity record(String merchantId, long amountMinor, String currency) {
        String id = "PAY-" + UUID.randomUUID();
        PaymentEntity entity = new PaymentEntity(id, merchantId, amountMinor, currency, Instant.now());
        PaymentEntity saved = paymentRepository.save(entity);
        logAuditTrail(saved);
        return saved;
    }

    private void logAuditTrail(PaymentEntity saved) {
        log.info("audit-trail: payment {} recorded for merchant {}, amount {} {}",
                saved.getId(), saved.getMerchantId(), saved.getAmountMinor(), saved.getCurrency());
    }

    @Transactional(readOnly = true)
    public PaymentEntity findById(String paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    @Transactional(readOnly = true)
    public long settlementFor(String merchantId) {
        List<PaymentEntity> payments = paymentRepository.findByMerchantId(merchantId);
        if (payments.isEmpty()) {
            throw new MerchantNotFoundException(merchantId);
        }
        long gross = payments.stream().mapToLong(PaymentEntity::getAmountMinor).sum();
        long fee = gross * feeRateBasisPoints / 10_000;
        return gross - fee;
    }
}