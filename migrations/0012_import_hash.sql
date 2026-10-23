-- Links are refreshed every hour and most refreshes find the same calendar. Each import stores a hash of what it
-- imported (and the day), so an unchanged calendar is skipped instead of rewriting every session: the free plan
-- allows 100k rows written a day. A changed calendar, or the first refresh of the day, imports in full.
ALTER TABLE calendar_sources ADD COLUMN import_hash TEXT;
