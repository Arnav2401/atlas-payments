package com.atlas.payments.fraud;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    /**
     * A flagged payment starts {@code UNDER_REVIEW} automatically — see the
     * constructor — because a fraud model flagging a payment IS the request
     * for review; an analyst does not need to separately ask for review on
     * something the system already flagged. An unflagged payment starts
     * {@code NONE}; {@code POST /payments/{id}/review} still lets an analyst
     * pull an unflagged payment into review manually.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 16)
    private ReviewStatus reviewStatus;

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
        this.reviewStatus = flagged ? ReviewStatus.UNDER_REVIEW : ReviewStatus.NONE;
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

    public ReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    /**
     * An analyst's action: pull this payment into review. Idempotent — moving
     * an already-under-review payment to UNDER_REVIEW again is a no-op, not
     * an error, since two analysts requesting review on the same payment is a
     * normal race, not a conflict either of them needs to see.
     *
     * @throws IllegalStateException if the payment already has a supervisor's
     *         terminal decision — review cannot un-resolve a resolved payment.
     */
    public void requestReview() {
        if (reviewStatus == ReviewStatus.CLEARED || reviewStatus == ReviewStatus.ESCALATED) {
            throw new ReviewConflictException(
                    "payment " + paymentId + " is already resolved (" + reviewStatus + ") and cannot be reopened");
        }
        reviewStatus = ReviewStatus.UNDER_REVIEW;
    }

    /** A supervisor's action: this payment was legitimate. */
    public void clear() {
        assertResolvable("clear");
        reviewStatus = ReviewStatus.CLEARED;
    }

    /** A supervisor's action: this payment needs action outside this system. */
    public void escalate() {
        assertResolvable("escalate");
        reviewStatus = ReviewStatus.ESCALATED;
    }

    private void assertResolvable(String action) {
        if (reviewStatus == ReviewStatus.CLEARED || reviewStatus == ReviewStatus.ESCALATED) {
            throw new ReviewConflictException(
                    "payment " + paymentId + " is already resolved (" + reviewStatus + ") and cannot " + action);
        }
    }
}
