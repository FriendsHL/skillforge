package com.skillforge.server.improve;

/**
 * Plan r2 §9 + Code Judge r1 B-FE-2 — thrown when {@code SkillDraftService.approveDraft}
 * sees a high-similarity (≥ {@code DEDUP_HIGH}) draft and the caller did not pass
 * {@code forceCreate=true}.
 * <p>The controller maps this to HTTP 409 Conflict with a structured body so the FE
 * can drive its Modal.confirm + forceCreate flow.
 */
public class HighSimilarityRejectedException extends RuntimeException {

    private final double similarity;
    private final Long candidateId;
    private final String candidateName;

    public HighSimilarityRejectedException(String message, double similarity,
                                           Long candidateId, String candidateName) {
        super(message);
        this.similarity = similarity;
        this.candidateId = candidateId;
        this.candidateName = candidateName;
    }

    public double getSimilarity() {
        return similarity;
    }

    public Long getCandidateId() {
        return candidateId;
    }

    public String getCandidateName() {
        return candidateName;
    }
}
