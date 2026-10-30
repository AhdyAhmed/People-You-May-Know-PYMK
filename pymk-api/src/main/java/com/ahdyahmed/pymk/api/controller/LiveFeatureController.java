package com.ahdyahmed.pymk.api.controller;

import com.ahdyahmed.pymk.api.service.MemberService;
import com.ahdyahmed.pymk.featurestore.LiveFeatureStore;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import jakarta.validation.constraints.Positive;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/members")
public class LiveFeatureController {
    private final LiveFeatureStore features;
    private final MemberService members;
    private final Clock clock;
    private final int windowDays;
    public LiveFeatureController(LiveFeatureStore features, MemberService members, Clock clock,
            @Value("${pymk.live-features.window-days:30}") int windowDays) {
        if (windowDays < 1 || windowDays > 365) throw new IllegalArgumentException("Live window must be 1..365 days");
        this.features = features; this.members = members; this.clock = clock; this.windowDays = windowDays;
    }

    @GetMapping("/{id}/live-features/{candidateId}")
    public LiveFeatureStore.Counters counters(@Positive @PathVariable long id,
                                             @Positive @PathVariable long candidateId) {
        if (id == candidateId) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pair must be distinct");
        members.getMember(id);
        members.getMember(candidateId);
        var now = clock.instant();
        return features.counters(id, candidateId, now.minus(windowDays, ChronoUnit.DAYS), now, now);
    }
}
