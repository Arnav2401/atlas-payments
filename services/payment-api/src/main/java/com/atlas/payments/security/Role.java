package com.atlas.payments.security;

/**
 * The brief's two roles: {@code ANALYST} (view, request review) and
 * {@code SUPERVISOR} (clear, escalate) — see
 * {@link com.atlas.payments.fraud.ReviewStatus} for where that distinction is
 * actually enforced.
 */
public enum Role {
    ANALYST,
    SUPERVISOR
}
