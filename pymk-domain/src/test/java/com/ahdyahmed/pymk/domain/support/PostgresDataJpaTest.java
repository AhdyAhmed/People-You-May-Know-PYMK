package com.ahdyahmed.pymk.domain.support;

import com.ahdyahmed.pymk.domain.repository.EmbeddingSearchRepository;
import com.ahdyahmed.pymk.domain.service.ConnectionService;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * Meta-annotation for repository tests: real Postgres (not H2), Flyway
 * migrations applied, entity mappings validated against the migrated schema.
 *
 * <p>Every test class uses the exact same configuration, so Spring's context
 * cache reuses one application context, and therefore one container, for the
 * whole module.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfig.class, ConnectionService.class, EmbeddingSearchRepository.class})
public @interface PostgresDataJpaTest {
}
