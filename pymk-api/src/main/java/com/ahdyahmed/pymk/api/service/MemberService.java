package com.ahdyahmed.pymk.api.service;

import com.ahdyahmed.pymk.api.dto.MemberResponse;
import com.ahdyahmed.pymk.api.error.MemberNotFoundException;
import com.ahdyahmed.pymk.domain.repository.ConnectionRepository;
import com.ahdyahmed.pymk.domain.repository.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-side member operations for the public API. */
@Service
public class MemberService {

    private final MemberRepository members;
    private final ConnectionRepository connections;

    public MemberService(MemberRepository members, ConnectionRepository connections) {
        this.members = members;
        this.connections = connections;
    }

    @Transactional(readOnly = true)
    public MemberResponse getMember(long id) {
        return members.findById(id)
                .map(m -> MemberResponse.from(m, connections.countByMemberId(id)))
                .orElseThrow(() -> new MemberNotFoundException(id));
    }
}
