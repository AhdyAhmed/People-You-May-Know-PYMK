package com.ahdyahmed.pymk.domain.repository;

import com.ahdyahmed.pymk.domain.entity.EventType;
import com.ahdyahmed.pymk.domain.entity.MemberEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

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

    boolean existsByActorMemberIdAndTargetMemberIdAndType(
            Long actorMemberId, Long targetMemberId, EventType type);
}
