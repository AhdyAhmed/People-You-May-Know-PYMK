package com.ahdyahmed.pymk.candidategen.support;

import com.ahdyahmed.pymk.domain.entity.Member;
import com.ahdyahmed.pymk.domain.repository.MemberRepository;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Test bootstrap for the library module and its domain dependency. */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackageClasses = Member.class)
@EnableJpaRepositories(basePackageClasses = MemberRepository.class)
public class CandidateGenTestApplication {
}
