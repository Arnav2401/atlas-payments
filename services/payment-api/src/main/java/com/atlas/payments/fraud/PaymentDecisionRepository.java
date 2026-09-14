package com.atlas.payments.fraud;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentDecisionRepository extends JpaRepository<PaymentDecisionEntity, Long> {

    Optional<PaymentDecisionEntity> findByPaymentId(UUID paymentId);
}
