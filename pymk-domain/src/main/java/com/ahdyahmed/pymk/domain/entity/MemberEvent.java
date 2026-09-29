package com.ahdyahmed.pymk.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * An implicit or explicit engagement event between two members (profile
 * view, search appearance, invite lifecycle). Matches PYMK_DESIGN.md section
 * 5.1.
 *
 * <p>This is the raw event log; {@code FeatureComputationJob} (Day 17)
 * aggregates it into {@code pymk_features}, and the label-generation step
 * (Day 18) reads {@code INVITE_ACCEPTED} rows as positive training labels.</p>
 */
@Entity
@Table(
        name = "member_events",
        indexes = {
                @Index(name = "idx_event_actor", columnList = "actor_member_id"),
                @Index(name = "idx_event_target_type", columnList = "target_member_id, type"),
                @Index(name = "idx_event_occurred_at", columnList = "occurred_at")
        }
)
public class MemberEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_member_id", nullable = false)
    private Long actorMemberId;

    @Column(name = "target_member_id", nullable = false)
    private Long targetMemberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private EventType type;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected MemberEvent() {
        // required by JPA
    }

    public MemberEvent(Long actorMemberId, Long targetMemberId, EventType type, Instant occurredAt) {
        this.actorMemberId = actorMemberId;
        this.targetMemberId = targetMemberId;
        this.type = type;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public Long getActorMemberId() {
        return actorMemberId;
    }

    public Long getTargetMemberId() {
        return targetMemberId;
    }

    public EventType getType() {
        return type;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MemberEvent that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "MemberEvent{id=%s, actorMemberId=%s, targetMemberId=%s, type=%s, occurredAt=%s}"
                .formatted(id, actorMemberId, targetMemberId, type, occurredAt);
    }
}
