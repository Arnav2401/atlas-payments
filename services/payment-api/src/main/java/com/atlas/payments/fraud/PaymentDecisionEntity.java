package com.atlas.payments.fraud;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * The durable, asynchronous record of a fraud verdict — distinct from M3's
 * synchronous {@code FraudAssessment}, which is never persisted and exists
 * only for the length of one HTTP response. This is the row
 * {@link PaymentDecisionConsumer} writes after consuming {@code
 * payments.decisioned}, and {@code payment_id}'s unique constraint is the
 * whole idempotency mechanism — see that class's javadoc for why at-least-once
 * delivery makes this constraint load-bearing rather than defensive.
 */
@Entity
@Table(name = "payment_decisions")
public class PaymentDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false, unique = true)
    private UUID paymentId;

    @Column(nullable = false)
    private String source;

    @Column(nullable = false)
    private boolean flagged;

    private Double probability;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    /** The raw event, kept verbatim for audit — see OutboxEntity's payload for the same reasoning. */
    @Column(name = "raw_payload", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String rawPayload;

    protected PaymentDecisionEntity() {
    }

    public PaymentDecisionEntity(UUID paymentId, String source, boolean flagged, Double probability,
                                 Instant decidedAt, String rawPayload) {
        this.paymentId = paymentId;
        this.source = source;
        this.flagged = flagged;
        this.probability = probability;
        this.decidedAt = decidedAt;
        this.rawPayload = rawPayload;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public boolean isFlagged() {
        return flagged;
    }

    public Double getProbability() {
        return probability;
    }

    public String getSource() {
        return source;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
