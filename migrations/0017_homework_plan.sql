-- Time blocking: when you plan to work on a to-do (the Calendar page), separate from when it's due.
-- planned_at is the block's start (ms), planned_minutes its length (NULL = the item's effort).
ALTER TABLE homework ADD COLUMN planned_at INTEGER;
ALTER TABLE homework ADD COLUMN planned_minutes INTEGER;
