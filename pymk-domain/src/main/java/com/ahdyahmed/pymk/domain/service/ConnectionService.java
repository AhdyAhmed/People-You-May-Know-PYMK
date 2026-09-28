package com.ahdyahmed.pymk.domain.service;

import com.ahdyahmed.pymk.domain.entity.Connection;
import com.ahdyahmed.pymk.domain.repository.ConnectionRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the "an undirected connection is two rows" invariant so no caller
 * ever writes half an edge. Used by {@code POST /api/v1/connections} (Day 6)
 * and the synthetic data generator (Day 4).
 *
 * <p>Both rows are written in one transaction. The
 * {@code uq_connections_pair} constraint is the backstop if two concurrent
 * requests race past the existence check.</p>
 */
@Service
public class ConnectionService {

    private final ConnectionRepository connections;

    public ConnectionService(ConnectionRepository connections) {
        this.connections = connections;
    }

    /**
     * Creates both directions of the edge; idempotent.
     *
     * @return true if at least one row was created, false if the members were
     *         already fully connected
     */
    @Transactional
    public boolean connect(long memberA, long memberB, Instant connectedAt) {
        if (memberA == memberB) {
            throw new IllegalArgumentException("A member cannot connect to themselves: " + memberA);
        }
        boolean created = false;
        if (!connections.existsByMemberIdAndConnectedMemberId(memberA, memberB)) {
            connections.save(new Connection(memberA, memberB, connectedAt));
            created = true;
        }
        if (!connections.existsByMemberIdAndConnectedMemberId(memberB, memberA)) {
            connections.save(new Connection(memberB, memberA, connectedAt));
            created = true;
        }
        return created;
    }

    /** Removes both directions of the edge. */
    @Transactional
    public void disconnect(long memberA, long memberB) {
        connections.deleteByMemberIdAndConnectedMemberId(memberA, memberB);
        connections.deleteByMemberIdAndConnectedMemberId(memberB, memberA);
    }
}
