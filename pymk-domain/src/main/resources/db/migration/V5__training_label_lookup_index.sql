-- V5: Day 18 label-export lookup path.
-- Supports the correlated directional acceptance lookup for each feature row:
-- pair equality, event type equality, then a bounded occurred_at range.
CREATE INDEX idx_event_pair_type_time
    ON member_events (actor_member_id, target_member_id, type, occurred_at);
