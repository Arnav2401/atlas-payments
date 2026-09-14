package com.atlas.payments.outbox;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEntity, Long> {

    /**
     * The poller's whole query. {@code Limit} caps a batch rather than pulling
     * every undispatched row at once — under normal operation this is a
     * handful of rows; after an extended fraud-service or Kafka outage it
     * could be a large backlog, and publishing it one bounded batch per poll
     * keeps a single poll cycle's transaction and memory footprint predictable
     * regardless of how large that backlog got.
     */
    @Query("SELECT o FROM OutboxEntity o WHERE o.dispatchedAt IS NULL ORDER BY o.createdAt ASC")
    List<OutboxEntity> findUndispatchedBatch(Limit limit);
}
