# Android app

A Trusted Web Activity: a small Android app that opens epita.knhdixh.com in Chrome, full screen, with its own
icon. Passkeys, Google sign-in, notifications and the Focus timer work as on the website, and every deploy
updates the app; a new APK is only needed when `twa-manifest.json` changes. Built with
[Bubblewrap](https://github.com/GoogleChromeLabs/bubblewrap).

Needs JDK 17 (Bubblewrap downloads the Android SDK the first time). The production site must already serve
`/manifest.webmanifest` and the icons.

```bash
cd android
# Once: the signing key. Keep android.keystore and its passwords safe: updates must be signed with the same key.
keytool -genkeypair -v -keystore android.keystore -alias android -keyalg RSA -keysize 2048 -validity 10000
npx @bubblewrap/cli update --skipVersionUpgrade   # generates the Android project from twa-manifest.json
npx @bubblewrap/cli build                         # app-release-signed.apk (sideload) and app-release-bundle.aab (Play)
```

To open full screen (no URL bar), the site must vouch for the key. Get its SHA-256 fingerprint:

```bash
keytool -list -v -keystore android.keystore -alias android | grep SHA256
```

Put it in `ANDROID_CERT_SHA256` in `wrangler.jsonc` (both environments; comma-separate several, e.g. the Play
upload key too) and deploy. The Worker then serves `/.well-known/assetlinks.json`.

For a new version, bump `appVersionCode` (and `appVersionName`) in `twa-manifest.json`, then `update` and `build`.
