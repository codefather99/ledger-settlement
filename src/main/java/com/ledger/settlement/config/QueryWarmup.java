package com.ledger.settlement.config;

import com.ledger.settlement.repository.PaymentRepository;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Runs the settlement query once on the main platform thread during startup, before the web
 * server accepts traffic. Hibernate parses a derived query and initialises its HQL parser
 * classes on first use; done on a request's virtual thread, that class initialisation pins
 * the carrier (seen as jdk.VirtualThreadPinned in the cold-start recording).
 * No data is read that matters: the merchant id does not exist.
 */
@Component
class QueryWarmup implements SmartInitializingSingleton {

    private final PaymentRepository paymentRepository;

    QueryWarmup(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Override
    public void afterSingletonsInstantiated() {
        paymentRepository.findByMerchantId("__warmup__");
    }
}