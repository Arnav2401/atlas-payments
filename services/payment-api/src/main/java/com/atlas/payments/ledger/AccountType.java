package com.atlas.payments.ledger;

public enum AccountType {

    /** A customer deposit account. Subject to the available-funds check. */
    CUSTOMER,

    /**
     * The bank's own position. Exempt from the funds check — applying a customer
     * overdraft rule to a nostro position would be meaningless, and it is the
     * account that funding entries draw against.
     */
    SETTLEMENT
}
