package com.ahdyahmed.pymk.domain;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only bootstrap. pymk-domain is a library module with no application
 * class of its own, but @DataJpaTest needs a @SpringBootConfiguration to
 * anchor entity and repository scanning.
 */
@SpringBootApplication
public class DomainTestApplication {
}
