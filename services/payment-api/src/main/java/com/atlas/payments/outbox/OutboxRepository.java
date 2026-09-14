package com.atlas.payments.outbox;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEntity, Long> {
    @Query("SELECT o FROM OutboxEntity o WHERE o.dispatchedAt IS NULL ORDER BY o.createdAt ASC")
    List<OutboxEntity> findUndispatchedBatch(Limit limit);
}
