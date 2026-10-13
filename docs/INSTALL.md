# Installing PocketPad on your phone

Download: **https://github.com/Dathaze20/PocketPad/releases/latest** → tap `PocketPad.apk`

Until PocketPad is published on the Play Store, Android treats it as an app from an unknown developer. Two safety screens can appear. Both are normal, and both are one-time.

## 1. "App blocked to protect your device" (Google Play Protect)

Play Protect blocks apps it hasn't seen before. Your own app is new to it, so it warns.

**Fix:** on that dialog, tap **More details** → **Install anyway**.

That option is present on current Android versions, and it is the whole fix. The "Install anyway" button is sometimes greyed out for a few seconds while Play Protect finishes scanning — wait a moment and it becomes tappable.

**Do not turn Play Protect off.** It is the scanner that protects every other app on the phone, and no install needs it disabled. If you cannot find "Install anyway":

- Make sure you are tapping **More details** first — the option is hidden behind it, under the large "Got it" button.
- Re-download the APK. A partial download can fail the scan.
- Install from a different file app (Samsung **My Files** rather than the browser's download prompt, or the reverse).
- Check the install permission in step 3 below; a missing permission can surface as a Play Protect-shaped dialog.

If none of that works, build the app yourself from source and install your own build (see the main README) rather than weakening the phone's security.

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
