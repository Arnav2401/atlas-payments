package com.atlas.payments.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PostingRepository extends JpaRepository<PostingEntity, Long> {

    @Query("SELECT COALESCE(SUM(p.amountMinor), 0) FROM PostingEntity p WHERE p.amountMinor > 0")
    long totalDebitsMinorUnits();

    @Query("SELECT COALESCE(SUM(p.amountMinor), 0) FROM PostingEntity p WHERE p.amountMinor < 0")
    long totalCreditsMinorUnits();

    @Query("SELECT COUNT(p) FROM PostingEntity p")
    long countPostings();
}
