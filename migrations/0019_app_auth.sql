-- One-time codes that hand a sign-in finished in the browser to the Android app (PKCE, 2 minutes).
CREATE TABLE app_codes (
  code        TEXT PRIMARY KEY,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  challenge   TEXT NOT NULL,      -- base64url SHA-256 of the app's verifier
  expires_at  INTEGER NOT NULL
);
