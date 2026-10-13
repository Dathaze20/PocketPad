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

They protect people from apps that sideload malware. PocketPad talks Bluetooth to your TV and USB to a plugged-in controller; its only internet use is the update check you start yourself from the settings menu, described below. Android can't tell any of that until the app is signed by a Play-registered developer. Publishing to the Play Store (see [PLAY_STORE_CHECKLIST.md](PLAY_STORE_CHECKLIST.md)) removes these screens for good.

---

## Already have PocketPad? Update from inside the app

From **2.4.0** onwards you do not need this page to update. In PocketPad, open the settings menu and choose **Check for updates**.

It tells you the version you have, asks GitHub whether a newer one is published, and says so either way. If there is one, you get its version number and size and a **Download and install** button — nothing is downloaded until you tap it, and nothing is installed until Android's own installer asks you to confirm.

The first time, Android asks you to allow PocketPad to install apps. That is the same one-time switch described in section 3 above, and the app offers to open it for you.

**Your pairings, custom layouts, background picture, controller style and haptics are all kept.** An update installs over the existing app; there is no uninstall and nothing is reset.

If the app says the release does not publish version information, that release was built before in-app updating existed — download the APK from the link at the top of this page once, and the next update will work from inside the app.
