-- A session can be in several calendars: a lecture for groups SE1, SE2 and SE3 has the same UID in each group's
-- Zeus link. events.source_id kept one owner (the last calendar to import it), so a group could show 0 sessions
-- and labels were wrong. Each calendar now records the sessions it contains, with the import that last saw them.
CREATE TABLE event_sources (
  event_id   INTEGER NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  source_id  INTEGER NOT NULL REFERENCES calendar_sources(id) ON DELETE CASCADE,
  seen_at    INTEGER NOT NULL,   -- stamp of the last import of this calendar that contained the session
  PRIMARY KEY (event_id, source_id)
) WITHOUT ROWID;
CREATE INDEX event_sources_source ON event_sources(source_id);

INSERT INTO event_sources (event_id, source_id, seen_at)
  SELECT id, source_id, seen_at FROM events WHERE source_id IS NOT NULL;

-- The single-owner model lost the other calendars of shared sessions: re-import every link on the next cron runs
-- (one per 15 minutes) to restore them. Uploaded files keep their current sessions until uploaded again.
UPDATE calendar_sources SET synced_at = NULL WHERE url IS NOT NULL;

DROP INDEX events_source;
ALTER TABLE events DROP COLUMN source_id;
ALTER TABLE events DROP COLUMN seen_at;
