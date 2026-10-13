-- Passkey (WebAuthn) sign-in. Passkey-only accounts have google_sub = 'passkey:<handle>'.
ALTER TABLE users ADD COLUMN webauthn_id TEXT;               -- base64url WebAuthn user handle
CREATE UNIQUE INDEX users_webauthn_id ON users(webauthn_id);

CREATE TABLE passkeys (
  id            TEXT PRIMARY KEY,                             -- credential id, base64url
  user_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  public_key    TEXT NOT NULL,                                -- COSE public key, base64url
  counter       INTEGER NOT NULL DEFAULT 0,
  transports    TEXT NOT NULL DEFAULT '[]',
  backed_up     INTEGER NOT NULL DEFAULT 0,                   -- synced passkey (iCloud, Google, 1Password…)
  name          TEXT NOT NULL DEFAULT '',
  created_at    INTEGER NOT NULL,
  last_used_at  INTEGER
);
CREATE INDEX passkeys_user ON passkeys(user_id);

-- One-time WebAuthn challenges (5 minutes), referenced by an HttpOnly cookie.
CREATE TABLE auth_challenges (
  id          TEXT PRIMARY KEY,
  challenge   TEXT NOT NULL,
  kind        TEXT NOT NULL,                                  -- register | login
  user_id     INTEGER,                                        -- set when adding a passkey to an existing account
  user_handle TEXT,
  name        TEXT,
  expires_at  INTEGER NOT NULL
);
