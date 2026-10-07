package com.ahdyahmed.pymk.domain.repository;

import com.ahdyahmed.pymk.domain.entity.Connection;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Adjacency access over the symmetric edge list. Because every connection is
 * stored as two rows, "who is member X connected to" is a single indexed
 * lookup on {@code member_id} with no OR / UNION across both columns.
 */
public interface ConnectionRepository extends JpaRepository<Connection, Long> {

    List<Connection> findByMemberId(Long memberId);

    /** Neighbor IDs only, avoids hydrating full entities on the hot path. */
    @Query("select c.connectedMemberId from Connection c where c.memberId = :memberId")
    List<Long> findConnectedMemberIds(@Param("memberId") Long memberId);

    long countByMemberId(Long memberId);

    boolean existsByMemberIdAndConnectedMemberId(Long memberId, Long connectedMemberId);

    long deleteByMemberIdAndConnectedMemberId(Long memberId, Long connectedMemberId);

    /**
     * Number of members connected to both {@code memberId} and {@code otherId}
     * (the "mutual connections" signal, and later a feature in pymk_features).
     */
    @Query(nativeQuery = true, value = """
            SELECT COUNT(*)
            FROM connections a
            JOIN connections b ON a.connected_member_id = b.connected_member_id
            WHERE a.member_id = :memberId
              AND b.member_id = :otherId
            """)
    long countMutualConnections(@Param("memberId") Long memberId, @Param("otherId") Long otherId);

    /**
     * Fetches the mutual-connection signal for a whole candidate set in one
     * query. Candidates with no mutual connections are intentionally absent
     * from the result and are treated as zero by the caller.
     */
    @Query(nativeQuery = true, value = """
            SELECT b.member_id AS candidateId,
                   COUNT(*) AS mutualConnectionCount
            FROM connections a
            JOIN connections b
              ON a.connected_member_id = b.connected_member_id
            WHERE a.member_id = :memberId
              AND b.member_id IN (:candidateIds)
            GROUP BY b.member_id
            """)
    List<MutualConnectionCount> countMutualConnectionsForCandidates(
            @Param("memberId") Long memberId,
            @Param("candidateIds") Collection<Long> candidateIds);

    interface MutualConnectionCount {
        Long getCandidateId();

        long getMutualConnectionCount();
    }
}
