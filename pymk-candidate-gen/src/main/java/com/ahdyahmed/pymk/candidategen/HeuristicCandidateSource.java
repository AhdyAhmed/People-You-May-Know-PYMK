package com.ahdyahmed.pymk.candidategen;

import com.ahdyahmed.pymk.domain.entity.Member;
import com.ahdyahmed.pymk.domain.repository.MemberRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Candidate retrieval based on exact company, school, and region matches. */
@Service
public class HeuristicCandidateSource implements CandidateSource {

    private final MemberRepository members;
    private final CandidateEligibilityPolicy eligibility;

    public HeuristicCandidateSource(MemberRepository members, CandidateEligibilityPolicy eligibility) {
        this.members = members;
        this.eligibility = eligibility;
    }

    @Override
    public CandidateSourceType type() {
        return CandidateSourceType.HEURISTIC;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CandidateHit> generate(long memberId, int limit) {
        CandidateSource.validateRequest(memberId, limit);

        return members.findById(memberId)
                .map(member -> generateFor(member, limit))
                .orElseGet(List::of);
    }

    private List<CandidateHit> generateFor(Member member, int limit) {
        String company = meaningful(member.getCompany());
        String school = meaningful(member.getSchool());
        String geoRegion = meaningful(member.getGeoRegion());
        int availableAttributes = countAvailable(company, school, geoRegion);
        if (availableAttributes == 0) {
            return List.of();
        }

        List<CandidateHit> hits = members.findHeuristicCandidates(
                        member.getId(), company, school, geoRegion, PageRequest.of(0, limit))
                .stream()
                .map(candidate -> toHit(candidate, company, school, geoRegion, availableAttributes))
                .toList();
        return eligibility.filter(member.getId(), hits);
    }

    private CandidateHit toHit(
            Member candidate,
            String company,
            String school,
            String geoRegion,
            int availableAttributes) {
        List<String> matchedAttributes = new ArrayList<>(3);
        Map<String, String> metadata = new LinkedHashMap<>();
        if (company != null && company.equals(candidate.getCompany())) {
            matchedAttributes.add("company");
            metadata.put("company", company);
        }
        if (school != null && school.equals(candidate.getSchool())) {
            matchedAttributes.add("school");
            metadata.put("school", school);
        }
        if (geoRegion != null && geoRegion.equals(candidate.getGeoRegion())) {
            matchedAttributes.add("geoRegion");
            metadata.put("geoRegion", geoRegion);
        }
        metadata.put("matchedAttributes", String.join(",", matchedAttributes));
        metadata.put("matchCount", Integer.toString(matchedAttributes.size()));

        double score = (double) matchedAttributes.size() / availableAttributes;
        return new CandidateHit(candidate.getId(), type(), score, metadata);
    }

    private static int countAvailable(String... values) {
        int count = 0;
        for (String value : values) {
            if (value != null) {
                count++;
            }
        }
        return count;
    }

    private static String meaningful(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
