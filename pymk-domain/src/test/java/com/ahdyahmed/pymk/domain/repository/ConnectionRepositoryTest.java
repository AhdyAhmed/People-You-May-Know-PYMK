package com.ahdyahmed.pymk.domain.repository;

import static com.ahdyahmed.pymk.domain.support.TestData.member;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ahdyahmed.pymk.domain.entity.Connection;
import com.ahdyahmed.pymk.domain.service.ConnectionService;
import com.ahdyahmed.pymk.domain.support.PostgresDataJpaTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@PostgresDataJpaTest
class ConnectionRepositoryTest {

    @Autowired
    MemberRepository members;

    @Autowired
    ConnectionRepository connections;

    @Autowired
    ConnectionService connectionService;

    @BeforeEach
    void seedMembers() {
        members.saveAll(List.of(member(1L), member(2L), member(3L), member(4L), member(5L)));
        members.flush();
    }

    // ---- symmetric edge inserts -------------------------------------------------

    @Test
    void connectWritesBothDirectionsOfTheEdge() {
        boolean created = connectionService.connect(1L, 2L, Instant.now());
        connections.flush();

        assertThat(created).isTrue();
        assertThat(connections.findConnectedMemberIds(1L)).containsExactly(2L);
        assertThat(connections.findConnectedMemberIds(2L)).containsExactly(1L);
        assertThat(connections.count()).isEqualTo(2);
    }

    @Test
    void connectIsIdempotent() {
        connectionService.connect(1L, 2L, Instant.now());
        boolean createdAgain = connectionService.connect(2L, 1L, Instant.now());
        connections.flush();

        assertThat(createdAgain).isFalse();
        assertThat(connections.count()).isEqualTo(2);
    }

    @Test
    void connectRepairsAHalfWrittenEdge() {
        connections.saveAndFlush(new Connection(1L, 2L, Instant.now()));

        boolean created = connectionService.connect(1L, 2L, Instant.now());
        connections.flush();

        assertThat(created).isTrue();
        assertThat(connections.findConnectedMemberIds(2L)).containsExactly(1L);
        assertThat(connections.count()).isEqualTo(2);
    }

    @Test
    void disconnectRemovesBothDirections() {
        connectionService.connect(1L, 2L, Instant.now());
        connectionService.connect(1L, 3L, Instant.now());
        connections.flush();

        connectionService.disconnect(1L, 2L);
        connections.flush();

        assertThat(connections.findConnectedMemberIds(1L)).containsExactly(3L);
        assertThat(connections.findConnectedMemberIds(2L)).isEmpty();
    }

    @Test
    void connectingAMemberToThemselvesIsRejected() {
        assertThatThrownBy(() -> connectionService.connect(1L, 1L, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- database-level guarantees ----------------------------------------------

    @Test
    void databaseRejectsDuplicateDirectedEdges() {
        connections.saveAndFlush(new Connection(1L, 2L, Instant.now()));

        assertThatThrownBy(() -> connections.saveAndFlush(new Connection(1L, 2L, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsSelfEdges() {
        assertThatThrownBy(() -> connections.saveAndFlush(new Connection(1L, 1L, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsEdgesToUnknownMembers() {
        assertThatThrownBy(() -> connections.saveAndFlush(new Connection(1L, 999L, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- query methods -----------------------------------------------------------

    @Test
    void adjacencyCountAndExistenceQueries() {
        connectionService.connect(1L, 2L, Instant.now());
        connectionService.connect(1L, 3L, Instant.now());
        connectionService.connect(1L, 4L, Instant.now());
        connections.flush();

        assertThat(connections.countByMemberId(1L)).isEqualTo(3);
        assertThat(connections.countByMemberId(5L)).isZero();
        assertThat(connections.findByMemberId(1L)).hasSize(3);
        assertThat(connections.existsByMemberIdAndConnectedMemberId(1L, 3L)).isTrue();
        assertThat(connections.existsByMemberIdAndConnectedMemberId(2L, 3L)).isFalse();
    }

    @Test
    void mutualConnectionCountIsSharedNeighborsOnly() {
        // 1 and 2 share neighbors 3 and 4. 5 is only a neighbor of 1.
        connectionService.connect(1L, 3L, Instant.now());
        connectionService.connect(1L, 4L, Instant.now());
        connectionService.connect(1L, 5L, Instant.now());
        connectionService.connect(2L, 3L, Instant.now());
        connectionService.connect(2L, 4L, Instant.now());
        connections.flush();

        assertThat(connections.countMutualConnections(1L, 2L)).isEqualTo(2);
        assertThat(connections.countMutualConnections(2L, 1L)).isEqualTo(2);
        assertThat(connections.countMutualConnections(1L, 5L)).isZero();
    }
}
