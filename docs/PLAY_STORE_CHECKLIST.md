# Publishing PocketPad on Google Play — Checklist

Everything needed to take this project from source code to a live Play Store listing.

## 1. One-time setup

- [ ] Create a [Google Play Developer account](https://play.google.com/console/signup) ($25 one-time fee).
- [ ] Install Android Studio and open this project.

## 2. App signing

- [ ] Generate an upload keystore (once, keep it safe forever):
  ```bash
  keytool -genkey -v -keystore pocketpad-upload.keystore \
      -alias pocketpad -keyalg RSA -keysize 2048 -validity 10000
  ```
- [ ] **Never commit the keystore or its passwords to git** (the `.gitignore` already excludes `*.keystore`).
- [ ] Add a signing config to `app/build.gradle` (or use Android Studio → *Build → Generate Signed Bundle*), reading passwords from `~/.gradle/gradle.properties` or environment variables:
  ```groovy
  signingConfigs {
      release {
          storeFile file(POCKETPAD_STORE_FILE)
          storePassword POCKETPAD_STORE_PASSWORD
          keyAlias "pocketpad"
          keyPassword POCKETPAD_KEY_PASSWORD
      }
  }
  buildTypes { release { signingConfig signingConfigs.release } }
  ```
- [ ] Opt into **Play App Signing** in the console (recommended — Google holds the final signing key, your keystore is just the upload key).

## 3. Build the release artifact

- [ ] Bump `versionCode` / `versionName` in `app/build.gradle` for each release.
- [ ] `./gradlew bundleRelease` → upload `app/build/outputs/bundle/release/app-release.aab`.
- [ ] Test the release build on a real phone first: `./gradlew assembleRelease` and sideload the APK.

## 4. Store listing

- [ ] App name: **PocketPad** (or your final choice — check for name collisions in the Play Store first).
- [ ] Short description (80 chars max), e.g. *"Turn your phone into a Bluetooth gamepad for your Smart TV."*
- [ ] Full description: cover touch controls, Samsung/Tizen TV pairing, PS3 controller passthrough via USB-C OTG.
- [ ] Screenshots: at least 2 phone screenshots (portrait + landscape controller). Take them with the app connected.
- [ ] Feature graphic 1024×500, app icon 512×512 (export from `ic_launcher` artwork).
- [ ] Category: **Tools** (or **Entertainment**).

## 5. Policy declarations (as of 2026)

- [ ] **Privacy policy URL** — required. PocketPad collects **no data**; a one-page static statement is enough (GitHub Pages works). State: no data collected, no data shared, Bluetooth/USB used only to transmit controller input locally.
- [ ] **Data safety form**: "No data collected", "No data shared". Bluetooth permission is used for core functionality (device pairing/input), not for location.
- [ ] **Permissions declaration**: `BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE` are runtime permissions with obvious purpose — no special declaration form needed (they are not in the sensitive-permissions list like SMS/location).
- [ ] **Content rating questionnaire**: utility app, no user content → typically rated *Everyone*.
- [ ] **Target API level**: this project targets SDK 36, which satisfies Play's current target-API requirement.
- [ ] **App access**: full access, no login required.
- [ ] **Ads**: declare "No ads".

## 6. Trademark caution

- Don't use Sony/PlayStation or Samsung logos, product photos, or trade dress in the icon, screenshots' marketing frames, or store graphics. Referring to compatibility in plain text ("works with a PS3 controller", "compatible with Samsung Smart TVs") is fine; implying endorsement is not.
- The in-app △○✕□ glyphs are generic geometric shapes rendered as text; keep it that way (no PlayStation logo images).

## 7. Rollout

- [ ] Start with **Internal testing** track (instant approval, up to 100 testers) → promote to **Closed/Open testing** → **Production**.
- [ ] New personal developer accounts must run a closed test with ≥12 testers for 14 days before production access — plan for this.
- [ ] After launch: watch *Android vitals* for crashes (the app has no network code, so expect a quiet dashboard).
