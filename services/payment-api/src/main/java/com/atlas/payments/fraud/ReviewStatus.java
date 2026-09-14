package com.atlas.payments.fraud;

/**
 * Gives the brief's analyst/supervisor split real teeth: an analyst can move
 * a payment to {@link #UNDER_REVIEW}; only a supervisor can resolve it.
 *
 * <p>{@link #CLEARED} and {@link #ESCALATED} are terminal — this API does not
 * transition a resolved payment further. A real system would need a reopen
 * path (a supervisor's clear turns out wrong); that is a workflow decision
 * deliberately left out here rather than guessed at.
 */
public enum ReviewStatus {
    /** No review needed — never flagged, or not flagged yet. */
    NONE,
    /** An analyst requested review, or the payment arrived flagged. */
    UNDER_REVIEW,
    /** A supervisor decided this was legitimate. Terminal. */
    CLEARED,
    /** A supervisor decided this needs further action outside this system. Terminal. */
    ESCALATED
}
