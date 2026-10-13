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

They protect people from apps that sideload malware. PocketPad talks Bluetooth to your TV and USB to a plugged-in controller; its only internet use is the update check you start yourself from the settings menu, described below. Android can't tell any of that until the app is signed by a Play-registered developer. Publishing to the Play Store (see [PLAY_STORE_CHECKLIST.md](PLAY_STORE_CHECKLIST.md)) removes these screens for good.

---

## Already have PocketPad? Update from inside the app

From **2.4.0** onwards you do not need this page to update. In PocketPad, open the settings menu and choose **Check for updates**.

> **2.4.0 itself has to be installed from this page.** The build before it has no Check for updates button, so it cannot fetch one. Download the APK from the link at the top once; every update after that can be done from inside the app.

It tells you the version you have, asks GitHub whether a newer one is published, and says so either way. If there is one, you get its version number and size and a **Download and install** button — nothing is downloaded until you tap it, and nothing is installed until Android's own installer asks you to confirm.

The first time, Android asks you to allow PocketPad to install apps. That is the same one-time switch described in section 3 above, and the app offers to open it for you.

**Your pairings, custom layouts, background picture, controller style and haptics are all kept.** An update installs over the existing app; there is no uninstall and nothing is reset.

If the app says the release does not publish version information, that release was built before in-app updating existed — download the APK from the link at the top of this page once, and the next update will work from inside the app.
