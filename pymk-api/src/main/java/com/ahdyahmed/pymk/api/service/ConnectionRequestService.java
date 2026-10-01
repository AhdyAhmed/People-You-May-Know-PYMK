package com.ahdyahmed.pymk.api.service;

import com.ahdyahmed.pymk.api.dto.ConnectionResponse;
import com.ahdyahmed.pymk.api.dto.CreateConnectionRequest;
import com.ahdyahmed.pymk.api.error.InvalidConnectionException;
import com.ahdyahmed.pymk.api.error.MemberNotFoundException;
import com.ahdyahmed.pymk.domain.repository.MemberRepository;
import com.ahdyahmed.pymk.domain.service.ConnectionService;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API-level orchestration for creating connections: validates the request
 * against the member table, then delegates to the domain
 * {@link ConnectionService}, which owns the "one connection = two rows"
 * invariant.
 */
@Service
public class ConnectionRequestService {

    private final MemberRepository members;
    private final ConnectionService connectionService;
    private final Clock clock;

    public ConnectionRequestService(MemberRepository members, ConnectionService connectionService, Clock clock) {
        this.members = members;
        this.connectionService = connectionService;
        this.clock = clock;
    }

    @Transactional
    public ConnectionResponse connect(CreateConnectionRequest request) {
        long a = request.memberId();
        long b = request.connectedMemberId();

        if (a == b) {
            throw new InvalidConnectionException("A member cannot connect to themselves: " + a);
        }
        if (!members.existsById(a)) {
            throw new MemberNotFoundException(a);
        }
        if (!members.existsById(b)) {
            throw new MemberNotFoundException(b);
        }

        boolean created = connectionService.connect(a, b, Instant.now(clock));
        return new ConnectionResponse(a, b, created);
    }
}
