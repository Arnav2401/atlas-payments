package com.atlas.payments.outbox;

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
 * One row is one event, not-yet-published or published exactly once from this
 * table's own point of view (see {@link com.atlas.payments.fraud.PaymentDecisionConsumer}
 * for why "published once" is not the same claim as "delivered exactly once"
 * end to end).
 *
 * <p>Written inside the same transaction that posts the payment — see
 * {@code LedgerWriter.write} — which is the entire mechanism the outbox
 * pattern rests on. This entity has no behaviour beyond that: no publish
 * logic, no Kafka dependency. It is a durable fact ("this event needs to go
 * out"), and durability is a property of the transaction it was written in,
 * not of anything this class does.
 */
@Entity
@Table(name = "outbox")
public class OutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(nullable = false)
    private String topic;

    /**
     * Stored as a JSON string, mapped to the column's native {@code jsonb}
     * type via Hibernate's {@code SqlTypes.JSON} — not a plain {@code TEXT}
     * column holding a JSON-shaped string. The distinction matters: {@code
     * jsonb} lets Postgres validate the value is well-formed JSON on write and
     * query into it later (for an ops dashboard, an audit query) without
     * parsing every row's text first.
     */
    @Column(nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    protected OutboxEntity() {
    }

    public OutboxEntity(UUID aggregateId, String topic, String payload, Instant createdAt) {
        this.aggregateId = aggregateId;
        this.topic = topic;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getTopic() {
        return topic;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }

    public void markDispatched(Instant now) {
        this.dispatchedAt = now;
    }
}
