-- Bring-your-own-key for the assistant: one provider per user. The key is AES-GCM encrypted with TOKEN_KEY
-- and never sent back to the browser.
CREATE TABLE ai_keys (
  user_id     INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  provider    TEXT NOT NULL,
  account_id  TEXT,                -- Cloudflare account id (Cloudflare only)
  api_key     TEXT NOT NULL,       -- encrypted
  key_hint    TEXT NOT NULL,       -- last 4 characters, for display
  model       TEXT NOT NULL,
  last_error  TEXT,
  created_at  INTEGER NOT NULL,
  last_used_at INTEGER
);

-- Messages sent with the user's own key don't count toward the shared allowance.
ALTER TABLE ai_usage ADD COLUMN byok_messages INTEGER NOT NULL DEFAULT 0;
