package com.atlas.payments.ledger;

/**
 * The debtor account does not hold enough to cover the payment.
 *
 * <p>A business decision, not a fault: the service evaluated the payment against
 * ledger state and decided against it. It is therefore reported the same way a
 * validation rejection is — HTTP 200 with {@code status: REJECTED} — under the
 * same rule as DECISION 1: the service produced a decision, and the decision is
 * the payload.
 */
public class InsufficientFundsException extends RuntimeException {

    /** Ledger-stage reason code. Distinct range from the ATLAS-V*** rule codes. */
    public static final String CODE = "ATLAS-L001";

    private final long availableMinorUnits;
    private final long requiredMinorUnits;

    public InsufficientFundsException(long availableMinorUnits, long requiredMinorUnits) {
        super("debtor account holds " + availableMinorUnits
                + " minor units; " + requiredMinorUnits + " required");
        this.availableMinorUnits = availableMinorUnits;
        this.requiredMinorUnits = requiredMinorUnits;
    }

    public long getAvailableMinorUnits() {
        return availableMinorUnits;
    }

    public long getRequiredMinorUnits() {
        return requiredMinorUnits;
    }
}
