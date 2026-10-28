package com.ahdyahmed.pymk.datagen.load;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BulkLoaderTest {
    @Test void pendingEventsPreventDestructiveReset() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("SELECT EXISTS"), eq(Boolean.class))).thenReturn(true);
        assertThatThrownBy(() -> new BulkLoader(jdbc).truncateAll())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unpublished");
        verify(jdbc, never()).execute(anyString());
    }

    @Test void completedReceiptsAndSequencesAreNotTruncated() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("SELECT EXISTS"), eq(Boolean.class))).thenReturn(false);
        when(jdbc.queryForObject("SELECT epoch FROM pymk_active_release", Long.class)).thenReturn(4L);
        new BulkLoader(jdbc).truncateAll();
        verify(jdbc).execute(argThat((String sql) -> sql.contains("TRUNCATE") &&
                !sql.contains("event_outbox") && !sql.contains("event_aggregate_sequence")));
        verify(jdbc).update("INSERT INTO pymk_active_release(singleton, epoch) VALUES (true, ?)", 5L);
    }
}
