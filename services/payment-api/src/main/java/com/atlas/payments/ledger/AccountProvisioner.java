package com.atlas.payments.ledger;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class AccountProvisioner {
    private final AccountRepository accounts;

    public AccountProvisioner(AccountRepository accounts) {
        this.accounts = accounts;
    }

    // Only this method is public, and it must be called from another bean:
    // Spring's proxy does not intercept self-invocation, so an in-class caller
    // would silently run without a transaction and poison the caller's one.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createIfAbsent(String accountNumber, String currencyCode, AccountType type, Instant now) {
        if (accounts.findByAccountNumber(accountNumber).isPresent()) {
            return;
        }
        accounts.save(new AccountEntity(accountNumber, currencyCode, type, now));
    }
}
