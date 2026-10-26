# Android app

A Trusted Web Activity (the site in Chrome, full screen, with its own icon) plus native pieces for the focus
timer:

- **Lock-screen countdown**: an ongoing notification that counts down by itself, with Pause, Skip and Stop. The end
  of each phase is an exact alarm, so it rings on time even if Chrome or the page is asleep.
- **Home-screen widget**: the class you're in or the next one, the top of "Do next", and the timer with Start/Pause.
- **Quick-settings tile**: start, pause or resume the timer.
- **Themed icon**: the launcher icon has a monochrome layer, so Android 13+ tints it with your wallpaper colours;
  on Android 12+ the widget uses them too.

The page and the app share the timer over Chrome's postMessage (`Bridge.java`, and the bridge section of
`public/focus-ui.js`): the newer timer wins, sessions the app finishes are recorded by the page, and the page sends
the widget a plan snapshot. The site must list `use_as_origin` for the app in `/.well-known/assetlinks.json`
(the Worker does, from `ANDROID_CERT_SHA256`).

The project started from [Bubblewrap](https://github.com/GoogleChromeLabs/bubblewrap) but is maintained by hand
now: **don't run `bubblewrap update`**, it would overwrite `app/src`. `twa-manifest.json` is kept for reference.

## Build

Needs JDK 17 and the Android SDK (build-tools 36.1.0, platform 36), e.g. in `~/.bubblewrap/android_sdk`.

```bash
cd android
echo "sdk.dir=$HOME/.bubblewrap/android_sdk" > local.properties
# keystore.properties (never commit it): storeFile=android.keystore, storePassword, keyAlias=android, keyPassword
./build.sh            # attendance80.apk, for epita.knhdixh.com
./build.sh staging    # attendance80-staging.apk, for epita-staging.knhdixh.com (same package: replaces the app)
adb install -r attendance80.apk
```

Keep `android.keystore` and its password (`signing.txt`) backed up: every update must be signed with the same key.
Its SHA-256 fingerprint is `ANDROID_CERT_SHA256` in `wrangler.jsonc`. For a new version, bump `versionCode` and
`versionName` in `app/build.gradle`.
