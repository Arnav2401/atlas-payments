package com.atlas.payments.validation;

/**
 * The ten validation rules, by stable internal identifier.
 *
 * <p>These names are internal. The wire-facing reason code is a separate,
 * published contract — see {@link #code()} — so that renaming a rule here is
 * not a breaking API change.
 *
 * <p>Rule set version 1. When this set changes, the versioning question from the
 * brief ("how would you version these rules?") becomes real: a rule set is a
 * {@code List<ValidationRule>}, so a v2 is a different list, not an edit to this
 * enum. Decide whether you version the set, the individual rule, or neither, and
 * record it in DESIGN_NOTES.md.
 */
public enum RuleId {

    /** Instructed amount must be strictly positive. */
    R01_AMOUNT_POSITIVE,

    /** Decimal places must match the currency's minor unit (JPY 0, most 2). */
    R02_AMOUNT_SCALE_MATCHES_CURRENCY,

    /** Instructed currency must be a live ISO 4217 code. */
    R03_CURRENCY_SUPPORTED,

    /** endToEndId present, non-empty, within max length. */
    R04_END_TO_END_ID,

    /** Debtor and creditor agent BICs must match ISO 9362 (8 or 11 alphanumeric). */
    R05_AGENT_BIC_FORMAT,

    /** Debtor and creditor accounts present and distinct. */
    R06_ACCOUNTS_PRESENT_AND_DISTINCT,

    /** chargeBearer must be one of the supported set. */
    R07_CHARGE_BEARER_SUPPORTED,

    /** Settlement date not in the past, not more than N days forward. */
    R08_SETTLEMENT_DATE_WINDOW,

    /** Debtor country must be a valid ISO 3166-1 alpha-2 code. */
    R09_DEBTOR_COUNTRY,

    /** Payload size and individual field lengths bounded. */
    R10_FIELD_LENGTH_BOUNDS;

    /**
     * The published reason code for this rule.
     *
     * <h2>Scheme</h2>
     *
     * <p>{@code ATLAS-Vnnn} — opaque and stable. Chosen over a mnemonic such as
     * {@code AMOUNT_NOT_POSITIVE} for one reason: a mnemonic describes the rule's
     * current meaning, so when the meaning drifts there is pressure to rename the
     * code, and the code is a published contract that clients branch on. An
     * opaque code cannot be "wrong" when a rule is re-scoped, so it never has to
     * change. The human-readable half lives in {@code message}, which is free to
     * change because nobody should be parsing it.
     *
     * <p>This is the same trade ISO 20022 makes with its external reason codes
     * ({@code AM02}, {@code RR02}) — opaque tokens plus a published table. The
     * {@code ATLAS-} prefix is deliberate: these are ours, and must not be
     * mistaken for ISO codes.
     *
     * <p><b>The rule:</b> a code, once published, never changes meaning. If a
     * rule is split, the old code stays with whichever half keeps the original
     * semantics and the new half gets a new number. Numbers are never reused.
     *
     * <p>{@code ATLAS-E***} is reserved for failures that occur before any rule
     * runs — see {@code ApiExceptionHandler}.
     */
    public String code() {
        return "ATLAS-V%03d".formatted(ordinal() + 1);
    }
}
