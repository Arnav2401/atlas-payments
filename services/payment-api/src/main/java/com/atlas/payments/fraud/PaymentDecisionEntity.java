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

    @Column(name = "raw_payload", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String rawPayload;

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

    public void requestReview() {
        if (reviewStatus == ReviewStatus.CLEARED || reviewStatus == ReviewStatus.ESCALATED) {
            throw new ReviewConflictException(
                    "payment " + paymentId + " is already resolved (" + reviewStatus + ") and cannot be reopened");
        }
        reviewStatus = ReviewStatus.UNDER_REVIEW;
    }

    public void clear() {
        assertResolvable("clear");
        reviewStatus = ReviewStatus.CLEARED;
    }

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
