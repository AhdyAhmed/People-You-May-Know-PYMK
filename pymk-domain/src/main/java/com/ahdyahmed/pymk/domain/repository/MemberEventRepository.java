package com.ahdyahmed.pymk.domain.repository;

import com.ahdyahmed.pymk.domain.entity.EventType;
import com.ahdyahmed.pymk.domain.entity.MemberEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Event log access. The pair-level, time-windowed count is the source of the
 * {@code profileViewCountLast30d} feature (Day 16), and INVITE_ACCEPTED
 * lookups feed training labels (Day 18).
 */
public interface MemberEventRepository extends JpaRepository<MemberEvent, Long> {

    List<MemberEvent> findByActorMemberId(Long actorMemberId);

    long countByTargetMemberIdAndType(Long targetMemberId, EventType type);

    long countByActorMemberIdAndTargetMemberIdAndTypeAndOccurredAtAfter(
            Long actorMemberId, Long targetMemberId, EventType type, Instant after);

    /** Counts directional pair events in the half-open window {@code (start, end]}. */
    @Query("""
            select count(e)
            from MemberEvent e
            where e.actorMemberId = :actorMemberId
              and e.targetMemberId = :targetMemberId
              and e.type = :type
              and e.occurredAt > :exclusiveStart
              and e.occurredAt <= :inclusiveEnd
            """)
    long countPairEventsInWindow(
            @Param("actorMemberId") Long actorMemberId,
            @Param("targetMemberId") Long targetMemberId,
            @Param("type") EventType type,
            @Param("exclusiveStart") Instant exclusiveStart,
            @Param("inclusiveEnd") Instant inclusiveEnd);

    boolean existsByActorMemberIdAndTargetMemberIdAndType(
            Long actorMemberId, Long targetMemberId, EventType type);
}
