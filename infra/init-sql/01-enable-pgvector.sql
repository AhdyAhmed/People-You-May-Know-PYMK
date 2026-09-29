-- Runs once, on first container init (see docker-compose.yml volume mount).
-- Confirmed working via `SELECT '[1,2,3]'::vector <-> '[3,2,1]'::vector;` (Day 5).
CREATE EXTENSION IF NOT EXISTS vector;
