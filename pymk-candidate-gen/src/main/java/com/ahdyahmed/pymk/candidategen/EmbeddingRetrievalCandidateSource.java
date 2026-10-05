package com.ahdyahmed.pymk.candidategen;

import com.ahdyahmed.pymk.domain.repository.EmbeddingSearchRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** L0 retrieval using pgvector cosine nearest-neighbor search. */
@Service
public class EmbeddingRetrievalCandidateSource implements CandidateSource {

    private final EmbeddingSearchRepository embeddings;
    private final CandidateEligibilityPolicy eligibility;

    public EmbeddingRetrievalCandidateSource(
            EmbeddingSearchRepository embeddings,
            CandidateEligibilityPolicy eligibility) {
        this.embeddings = embeddings;
        this.eligibility = eligibility;
    }

    @Override
    public CandidateSourceType type() {
        return CandidateSourceType.EMBEDDING;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CandidateHit> generate(long memberId, int limit) {
        CandidateSource.validateRequest(memberId, limit);
        List<CandidateHit> hits = embeddings.findNearestUnconnectedNeighbors(memberId, limit).stream()
                .map(neighbor -> new CandidateHit(
                        neighbor.memberId(),
                        type(),
                        1.0 - neighbor.cosineDistance(),
                        Map.of("cosineDistance", Double.toString(neighbor.cosineDistance()))))
                .toList();
        return eligibility.filter(memberId, hits);
    }
}
