# Installing PocketPad on your phone

Download: **https://github.com/Dathaze20/PocketPad/releases/latest** → tap `PocketPad.apk`

Until PocketPad is published on the Play Store, Android treats it as an app from an unknown developer. Two safety screens can appear. Both are normal, and both are one-time.

## 1. "App blocked to protect your device" (Google Play Protect)

Play Protect blocks apps it hasn't seen before. Your own app is new to it, so it warns.

**Fix:** on that dialog, tap **More details** → **Install anyway**.

If the dialog only offers "Got it" with no "Install anyway":

1. Open the **Play Store** app
2. Tap your **profile picture** (top right) → **Play Protect**
3. Tap the **gear icon** (settings, top right)
4. Turn **off** "Scan apps with Play Protect"
5. Install PocketPad
6. Turn Play Protect **back on** afterwards

## 2. "App not installed"

Two possible causes:

- **Play Protect blocked it** — see step 1 above. This is the most common cause.
- **An older PocketPad is still installed with a different signing key.** Builds before v1.4 were each signed with a throwaway key, so Android refuses to replace them.
  **Fix:** long-press the PocketPad icon → **Uninstall**, then install the new APK. From v1.4 onward the signing key is stable, so future versions install straight over the top.

## 3. "Install unknown apps" permission

If tapping the APK does nothing, Android needs permission for the app you're browsing files with:

**Settings → Apps → (My Files / Chrome — whichever you tapped the APK in) → Install unknown apps → Allow**

## Why these warnings exist

They protect people from apps that sideload malware. PocketPad has no internet access at all — it only talks Bluetooth to your TV and USB to a plugged-in controller — but Android can't tell that until the app is signed by a Play-registered developer. Publishing to the Play Store (see [PLAY_STORE_CHECKLIST.md](PLAY_STORE_CHECKLIST.md)) removes these screens for good.
