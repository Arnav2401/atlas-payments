package com.atlas.payments.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<AccountEntity, Long> {

    Optional<AccountEntity> findByAccountNumber(String accountNumber);

    /**
     * The balance, derived. There is no stored balance to read — this is the
     * only definition of what an account holds, so it cannot drift from the
     * postings that produced it.
     */
    @Query("""
            SELECT COALESCE(SUM(p.amountMinor), 0)
              FROM PostingEntity p
             WHERE p.account.accountNumber = :accountNumber
            """)
    long balanceMinorUnits(@Param("accountNumber") String accountNumber);
}
