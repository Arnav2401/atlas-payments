package com.atlas.payments.rings;

import java.util.List;

public record RingsResponse(boolean enabled, List<RingCandidateResponse> candidates) {}
