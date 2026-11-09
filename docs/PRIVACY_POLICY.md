# PocketPad Privacy Policy

*Last updated: November 2026*

PocketPad turns your phone into a Bluetooth game controller. Everything it does for you as a controller happens on your device.

## Data we collect

**None.** PocketPad does not collect, store, transmit, sell, or share any personal data. There is no analytics, no advertising, no account, and no tracking of any kind.

## Network access

PocketPad uses the internet for exactly one thing: **checking whether a newer PocketPad has been released**. That happens two ways.

**When you ask.** **Settings → Check for updates** always makes a fresh request, whenever you tap it.

**Automatically, at most once a day.** A few seconds after you open PocketPad, it quietly asks GitHub the same question. If you already have the newest version it says nothing at all. If a newer one is published it shows a small strip at the bottom of the screen with the version and download size, which you can dismiss — dismissing hides that version for 24 hours.

- The automatic check makes **at most one request per 24 hours**, and remembers the answer so reopening the app inside that window needs no request at all.
- It runs a few seconds after launch so it never competes with startup or with the Bluetooth connection, and it never interrupts a game.
- **Nothing downloads or installs by itself, ever.** The strip and the menu both open the same confirmation dialog, and the download only starts when you press it.
- There is **no background polling** — nothing runs when the app is closed — and **no system notification**. PocketPad does not ask for notification permission. The strip is a view inside the app.
- Being offline, GitHub being unavailable, or hitting a rate limit are all ignored silently and never shown to you.
- You can avoid the automatic request entirely by denying the app network access in Android's settings; everything else in PocketPad keeps working, because nothing else uses the network.
- It asks `api.github.com` for this project's newest public release, reads the version it publishes, and — only if you then tap **Download and install** — downloads the APK from GitHub.
- The request is an ordinary public one. It carries no identifier for you or your phone: no account, no device ID, no advertising ID, no installation ID. GitHub sees it the same way it sees anyone opening the releases page, which includes your IP address, as any web request does.
- **Nothing is uploaded.** No button presses, no controller input, no custom layouts, no background pictures, no Bluetooth addresses, no settings, no files. The traffic goes one way: a version number and an APK come down.

**Your controller input never touches the network.** Button presses travel over the Bluetooth HID link to the device you paired with, and over USB from a controller you plug in — exactly as before. The updater has nothing to do with it and cannot see it.

## Permissions we use and why

- **Bluetooth (Connect / Advertise):** used solely to pair with and send controller input to the device you choose (TV, tablet, PC). No Bluetooth information leaves your phone.
- **USB host access:** used solely to read input from a game controller you physically plug into your phone.
- **Vibration:** used solely for haptic button feedback.
- **Internet:** used solely for the update checks described above — the one you tap and the once-a-day automatic one. Nothing else in the app reaches the network.
- **Request install packages:** used solely to hand a downloaded PocketPad update to Android's own installer, which always asks you to confirm. PocketPad cannot install anything silently, and it will not install a file that is not PocketPad, is not newer than what you have, or does not match the checksum the release published.

## Data stored on your device

PocketPad remembers only your preferences (chosen controller style, custom layouts, background picture, vibration level, the Bluetooth address of the last device you connected to, and — for the update notice — when it last checked, which version it found, and which version you dismissed) in the app's private storage on your phone. Uninstalling the app deletes it. Installing an update does not — an update keeps all of it.

A downloaded update is written to the app's private cache and replaced by the next one. Nothing else on your phone is shared with any other app.

## Children

PocketPad collects no data from anyone, including children.

## Contact

Questions? Open an issue at https://github.com/Dathaze20/PocketPad/issues

---

*To publish this policy for the Play Store: enable GitHub Pages on this repository (Settings → Pages → deploy from branch → main → /docs) and use the resulting URL in the Play Console.*
