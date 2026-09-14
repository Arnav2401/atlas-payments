package com.atlas.payments.ledger;

public class InsufficientFundsException extends RuntimeException {
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
