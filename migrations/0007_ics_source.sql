-- Automatic calendar refresh: a Zeus subscription link ("Generate my link") the Worker re-imports on a schedule.
ALTER TABLE users ADD COLUMN ics_url TEXT;             -- normalised https://zeus.ionis-it.com/api/.../ics/<token>
ALTER TABLE users ADD COLUMN ics_from TEXT;            -- YYYY-MM-DD sent as startDate, so past classes stay in the import
ALTER TABLE users ADD COLUMN ics_synced_at INTEGER;    -- last attempt (claimed before fetching, so a failing link can't block the queue)
ALTER TABLE users ADD COLUMN ics_ok_at INTEGER;        -- last successful import
ALTER TABLE users ADD COLUMN ics_error TEXT;
CREATE INDEX users_ics_due ON users(ics_synced_at) WHERE ics_url IS NOT NULL;
