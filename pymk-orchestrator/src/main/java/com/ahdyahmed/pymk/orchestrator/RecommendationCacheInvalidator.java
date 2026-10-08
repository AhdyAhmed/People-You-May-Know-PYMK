package com.ahdyahmed.pymk.orchestrator;

import com.ahdyahmed.pymk.domain.repository.ConnectionRepository;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Computes graph-affected members and evicts their caches after commit. */
@Service
public class RecommendationCacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(RecommendationCacheInvalidator.class);

    private final ConnectionRepository connections;
    private final RecommendationCache cache;

    public RecommendationCacheInvalidator(ConnectionRepository connections, RecommendationCache cache) {
        this.connections = connections;
        this.cache = cache;
    }

    public Set<Long> affectedByConnection(long firstMemberId, long secondMemberId) {
        LinkedHashSet<Long> affected = new LinkedHashSet<>();
        affected.add(firstMemberId);
        affected.add(secondMemberId);
        try {
            affected.addAll(connections.findMembersWithinTwoHops(firstMemberId, secondMemberId));
        } catch (RuntimeException ex) {
            log.warn("Could not expand cache invalidation neighborhood; evicting endpoints only: {}",
                    ex.getMessage());
        }
        return Set.copyOf(affected);
    }

    public void evictAfterCommit(Collection<Long> memberIds) {
        Set<Long> immutableIds = Set.copyOf(memberIds);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.evictMembers(immutableIds);
                }
            });
        } else {
            cache.evictMembers(immutableIds);
        }
    }
}
