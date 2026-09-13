package com.atlas.payments.ledger;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * Conversion between the API's {@link BigDecimal} amounts and the ledger's
 * integer minor units.
 *
 * <p>The ledger stores a signed count of the currency's smallest unit — cents
 * for USD, whole yen for JPY, thousandths for BHD and KWD. Never a float, and
 * never a decimal that has to be rounded on the way in.
 *
 * <p>{@code longValueExact()} rather than {@code longValue()} is the whole
 * point: it throws instead of silently truncating. Given R02 has already proved
 * the amount's scale fits the currency, a throw here means a rule failed to do
 * its job, and that must surface as an error rather than as a payment for the
 * wrong amount.
 */
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
