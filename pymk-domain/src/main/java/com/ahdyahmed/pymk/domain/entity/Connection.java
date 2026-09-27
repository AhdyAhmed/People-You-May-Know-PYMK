package com.ahdyahmed.pymk.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * A directed edge in the connection graph. Matches PYMK_DESIGN.md section 5.1
 * / 5.2.
 *
 * <p>An accepted connection between A and B is stored as <b>two rows</b> —
 * {@code (A, B)} and {@code (B, A)} — trading storage for O(1) adjacency
 * lookups in either direction, which is what {@code GraphWalkCandidateSource}
 * (Day 9) needs for fast n-hop traversal via a recursive CTE.</p>
 */
@Entity
@Table(
        name = "connections",
        indexes = {
                @Index(name = "idx_conn_member", columnList = "member_id"),
                @Index(name = "idx_conn_connected", columnList = "connected_member_id")
        }
)
public class Connection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "connected_member_id", nullable = false)
    private Long connectedMemberId;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;

    protected Connection() {
        // required by JPA
    }

    public Connection(Long memberId, Long connectedMemberId, Instant connectedAt) {
        this.memberId = memberId;
        this.connectedMemberId = connectedMemberId;
        this.connectedAt = connectedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getMemberId() {
        return memberId;
    }

    public Long getConnectedMemberId() {
        return connectedMemberId;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Connection that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Connection{id=%s, memberId=%s, connectedMemberId=%s, connectedAt=%s}"
                .formatted(id, memberId, connectedMemberId, connectedAt);
    }
}
