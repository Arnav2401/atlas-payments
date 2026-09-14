package com.atlas.payments.ledger;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<AccountEntity, Long> {
    Optional<AccountEntity> findByAccountNumber(String accountNumber);

    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    @Query("SELECT a FROM AccountEntity a WHERE a.accountNumber = :accountNumber")
    Optional<AccountEntity> findForUpdate(@Param("accountNumber") String accountNumber);

    @Query("""
            SELECT COALESCE(SUM(p.amountMinor), 0)
              FROM PostingEntity p
             WHERE p.account.accountNumber = :accountNumber
            """)
    long balanceMinorUnits(@Param("accountNumber") String accountNumber);

    default long availableMinorUnits(String accountNumber) {
        return -balanceMinorUnits(accountNumber);
    }
}
