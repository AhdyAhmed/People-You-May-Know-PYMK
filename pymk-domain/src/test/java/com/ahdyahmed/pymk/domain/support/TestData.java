package com.ahdyahmed.pymk.domain.support;

import com.ahdyahmed.pymk.domain.entity.Member;
import java.time.Instant;

public final class TestData {

    private TestData() {
    }

    public static Member member(long id) {
        return member(id, "Company-" + id, "School-" + id, "Region-" + id);
    }

    public static Member member(long id, String company, String school, String geoRegion) {
        return new Member(id, "Member " + id, "Headline " + id, company, school, geoRegion, Instant.now());
    }
}
