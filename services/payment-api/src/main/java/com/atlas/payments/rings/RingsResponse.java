package com.atlas.payments.rings;

import java.util.List;

/**
 * {@code enabled=false} means the fraud service has no Neo4j configured
 * (see its own config.py) — not an error, and not this endpoint's problem to
 * fix; the ops console renders that state explicitly rather than an empty
 * list indistinguishable from "no suspicious accounts found".
 */
public record RingsResponse(boolean enabled, List<RingCandidateResponse> candidates) {}
