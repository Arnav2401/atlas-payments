package com.atlas.payments.ledger;

import java.math.BigDecimal;
import java.util.Currency;

public final class Money {
    private Money() {}

    public static long toMinorUnits(BigDecimal amount, Currency currency) {
        int minorUnits = currency.getDefaultFractionDigits();
        if (minorUnits < 0) {
            throw new IllegalArgumentException(
                    currency.getCurrencyCode() + " has no minor unit and cannot be posted");
        }
        return amount.movePointRight(minorUnits).longValueExact();
    }

    public static BigDecimal fromMinorUnits(long minor, Currency currency) {
        return BigDecimal.valueOf(minor).movePointLeft(currency.getDefaultFractionDigits());
    }
}
