package com.atlas.payments.ledger;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<AccountEntity, Long> {

    Optional<AccountEntity> findByAccountNumber(String accountNumber);

    /**
     * Reads the account and commits to a version, so that a concurrent change to
     * it makes this transaction fail rather than act on a stale read.
     *
     * <p>{@code OPTIMISTIC_FORCE_INCREMENT} rather than plain {@code OPTIMISTIC}
     * is the load-bearing detail. A plain optimistic lock only detects that
     * <em>someone else</em> modified the row. Here the danger is the opposite
     * shape: two payments both read the same unmodified account, both find the
     * balance sufficient, and both commit — nobody modified the account, so
     * nothing is detected, and the account is overdrawn. Forcing the version
     * increment makes each reader a writer of the row, so the second to commit
     * conflicts. That is what turns "I read this balance and decided on it" into
     * something the database can actually protect.
     */
    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    @Query("SELECT a FROM AccountEntity a WHERE a.accountNumber = :accountNumber")
    Optional<AccountEntity> findForUpdate(@Param("accountNumber") String accountNumber);

    /**
     * The balance, derived. There is no stored balance to read — this is the only
     * definition of what an account holds, so it cannot drift from the postings
     * that produced it.
     *
     * <p>Signed, in the ledger's convention: positive is a debit. A funded
     * customer account is negative, because their deposit is the bank's
     * liability. Use {@link #availableMinorUnits} for the amount a customer can
     * actually spend.
     */
    @Query("""
            SELECT COALESCE(SUM(p.amountMinor), 0)
              FROM PostingEntity p
             WHERE p.account.accountNumber = :accountNumber
            """)
    long balanceMinorUnits(@Param("accountNumber") String accountNumber);

    /**
     * What a customer can spend: the negation of the signed balance, because a
     * customer deposit is a credit balance in the bank's books.
     */
    default long availableMinorUnits(String accountNumber) {
        return -balanceMinorUnits(accountNumber);
    }
}
