package com.atlas.payments.rings;

public record RingCandidateResponse(
        String accountId,
        int community,
        int inDegree,
        int temporalSpreadHours,
        double suspicionScore,
        boolean planted
) {
    static RingCandidateResponse from(RingsApiDto.Candidate candidate) {
        return new RingCandidateResponse(
                candidate.accountId(),
                candidate.community(),
                candidate.inDegree(),
                candidate.temporalSpreadHours(),
                candidate.suspicionScore(),
                candidate.planted());
    }
}
