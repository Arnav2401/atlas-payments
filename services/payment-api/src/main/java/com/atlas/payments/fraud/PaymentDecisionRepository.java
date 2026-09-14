package com.atlas.payments.fraud;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentDecisionRepository extends JpaRepository<PaymentDecisionEntity, Long> {
    Optional<PaymentDecisionEntity> findByPaymentId(UUID paymentId);

    List<PaymentDecisionEntity> findAllByPaymentIdIn(Collection<UUID> paymentIds);
}
