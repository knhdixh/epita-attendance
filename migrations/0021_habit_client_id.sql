-- A retried "add habit" from the app's outbox must not add it twice: the same client_id returns the habit already made
-- (as for tasks, projects and the rest in 0020). Additive: old rows have none.
ALTER TABLE habits ADD COLUMN client_id TEXT;
CREATE UNIQUE INDEX habits_client ON habits(user_id, client_id) WHERE client_id IS NOT NULL;
