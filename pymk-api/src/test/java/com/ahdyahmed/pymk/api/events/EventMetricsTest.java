package com.ahdyahmed.pymk.api.events;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class EventMetricsTest {
    @Test void tagsAreBoundedAndTimersSeparateFailuresFromSuccessfulProcessing() {
        var registry = new SimpleMeterRegistry();
        var metrics = new EventMetrics(registry);
        metrics.send(System.nanoTime(), false); metrics.send(System.nanoTime(), true);
        metrics.process("untrusted-id", System.nanoTime(), false);
        metrics.retry("another-id"); metrics.deadLetter("third-id", false);
        assertThat(registry.get("pymk.events.outbox.send").tag("outcome", "acknowledged").timer().count()).isEqualTo(1);
        assertThat(registry.get("pymk.events.outbox.send").tag("outcome", "failed").timer().count()).isEqualTo(1);
        assertThat(registry.get("pymk.events.consumer.process").tag("group", "other").timer().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().toString()).doesNotContain("untrusted-id", "another-id", "third-id"));
    }
    @Test void unavailableDatabaseNeverReportsHealthyZeroAndScrapingDoesNotQueryIt() throws Exception {
        var source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new java.sql.SQLException("offline"));
        var registry = new SimpleMeterRegistry();
        var monitor = new EventMonitor(source, registry, 1000, false, "unused:1");
        try {
            assertThat(registry.get("pymk.events.outbox.pending").gauge().value()).isNaN();
            monitor.poll();
            assertThat(registry.get("pymk.events.monitor.up").tag("source", "database").gauge().value()).isZero();
            assertThat(registry.get("pymk.events.outbox.pending").gauge().value()).isNaN();
            clearInvocations(source);
            registry.getMeters().forEach(meter -> meter.measure().forEach(measurement -> measurement.getValue()));
            verifyNoInteractions(source);
        } finally { monitor.close(); }
    }
    @Test void retainedLagHandlesNewGroupsRetentionGapsAndInvalidFutureOffsets() {
        assertThat(EventMonitor.retainedLag(10, 50, 10)).isEqualTo(40);
        assertThat(EventMonitor.retainedLag(10, 50, 20)).isEqualTo(30);
        assertThat(EventMonitor.retainedLag(10, 50, 0)).isEqualTo(40);
        assertThat(EventMonitor.retainedLag(10, 50, 60)).isZero();
    }
}
