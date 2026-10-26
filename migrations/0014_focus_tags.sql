-- Tags on focus sessions ("reading", "coding"): a JSON array of up to 5 lower-case strings, NULL for none.
-- The user's own tasks and tag list live in users.focus_settings (see DEFAULT_SETTINGS in public/focus.js).
ALTER TABLE focus_sessions ADD COLUMN tags TEXT;
