package com.ahdyahmed.pymk.domain.repository;

import static com.ahdyahmed.pymk.domain.support.TestData.member;
import static org.assertj.core.api.Assertions.assertThat;

import com.ahdyahmed.pymk.domain.entity.EventType;
import com.ahdyahmed.pymk.domain.entity.MemberEvent;
import com.ahdyahmed.pymk.domain.support.PostgresDataJpaTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@PostgresDataJpaTest
class MemberEventRepositoryTest {

    @Autowired
    MemberRepository members;

    @Autowired
    MemberEventRepository events;

    @BeforeEach
    void seedMembers() {
        members.saveAll(List.of(member(1L), member(2L), member(3L)));
        members.flush();
    }

    @Test
    void persistsEventTypeAsString() {
        MemberEvent saved = events.saveAndFlush(
                new MemberEvent(1L, 2L, EventType.INVITE_ACCEPTED, Instant.now()));

        assertThat(events.findById(saved.getId()).orElseThrow().getType())
                .isEqualTo(EventType.INVITE_ACCEPTED);
    }

    @Test
    void countsPairLevelProfileViewsInsideTheTimeWindowOnly() {
        Instant now = Instant.now();
        events.save(new MemberEvent(1L, 2L, EventType.PROFILE_VIEW, now.minus(Duration.ofDays(2))));
        events.save(new MemberEvent(1L, 2L, EventType.PROFILE_VIEW, now.minus(Duration.ofDays(10))));
        events.save(new MemberEvent(1L, 2L, EventType.PROFILE_VIEW, now.minus(Duration.ofDays(45))));
        events.save(new MemberEvent(3L, 2L, EventType.PROFILE_VIEW, now.minus(Duration.ofDays(1))));
        events.save(new MemberEvent(1L, 2L, EventType.SEARCH_APPEARANCE, now.minus(Duration.ofDays(1))));
        events.flush();

        long last30d = events.countByActorMemberIdAndTargetMemberIdAndTypeAndOccurredAtAfter(
                1L, 2L, EventType.PROFILE_VIEW, now.minus(Duration.ofDays(30)));

        assertThat(last30d).isEqualTo(2);
    }

    @Test
    void countsEventsByTargetAndType() {
        Instant now = Instant.now();
        events.save(new MemberEvent(1L, 2L, EventType.INVITE_ACCEPTED, now));
        events.save(new MemberEvent(3L, 2L, EventType.INVITE_ACCEPTED, now));
        events.save(new MemberEvent(1L, 3L, EventType.INVITE_ACCEPTED, now));
        events.save(new MemberEvent(1L, 2L, EventType.INVITE_IGNORED, now));
        events.flush();

        assertThat(events.countByTargetMemberIdAndType(2L, EventType.INVITE_ACCEPTED)).isEqualTo(2);
        assertThat(events.existsByActorMemberIdAndTargetMemberIdAndType(1L, 2L, EventType.INVITE_IGNORED))
                .isTrue();
        assertThat(events.existsByActorMemberIdAndTargetMemberIdAndType(2L, 1L, EventType.INVITE_IGNORED))
                .isFalse();
    }
}
