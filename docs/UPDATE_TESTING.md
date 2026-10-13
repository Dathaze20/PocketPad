# Verifying an in-app update on a phone — Galaxy A17

Automated tests cover the decision-making: what counts as a newer version, which URLs may be
fetched, what a release with no APK does, what happens to a download that arrives cut short.
They cannot cover the part that matters most to you — that the update installs over your copy
and your settings survive. That needs the phone.

## Read this first: what it takes to test an update at all

An in-app update needs **two builds**: the one running on your phone, and a *newer* one
published on GitHub. Android decides what is newer by `versionCode` alone.

| Build | versionName | versionCode | Has "Check for updates"? |
|---|---|--:|---|
| On your phone today | 2.3.0 | 17 | **No** |
| This PR | 2.4.0 | 18 | Yes |
| Needed to test an update | 2.4.1 or later | **19 or higher** | Yes |

Two consequences, and neither is optional:

1. **2.4.0 has to be installed by hand.** The copy on your phone is 2.3.0, which has no
   Check for updates button — it was built before any of this existed. Nothing in the app can
   fetch 2.4.0 for you. Download it from
   <https://github.com/Dathaze20/PocketPad/releases/latest> and install it the usual way.

2. **Installing 2.4.0 does not let you test updating.** Once 2.4.0 is on the phone, the
   published release *is* 2.4.0, so Check for updates will correctly say you are up to date.
   That is the right answer, and it is worth confirming — but it is not an update test. To
   test an actual update, a later build has to be published afterwards, with `versionCode`
   bumped to 19 or higher in `app/build.gradle`.

**That later release has not been created, and this PR does not create one.** Sections 1–3
below can be done as soon as 2.4.0 is installed. Sections 4–7 need that later build and have
to wait for it.

---

## Part A — after installing 2.4.0 by hand

### 1. Record what must survive

Before installing, note or screenshot:

- the controller style you use (Settings → Controller style)
- whether you have a custom layout saved (drag a button somewhere obvious, then SAVE, so you
  have an unmistakable marker)
- your background picture, if you set one
- your haptics level
- whether TV menu keys are on or off
- the device you are paired with, in your TV's Bluetooth list

Pair with the TV and press a few buttons, so you know the link works before you touch anything.

### 2. Confirm the manual install kept everything

Install 2.4.0 over 2.3.0 — there should be no prompt to uninstall first. Then check every item
from step 1 is exactly as you left it, and that the TV reconnects **without re-pairing**.

This is the same signature-compatibility question an in-app update asks, so it is worth doing
carefully even though the install was manual. If Android says **"App not installed"**, stop:
the signing key differs between the two builds, which should not happen — both are signed with
the repository's committed debug keystore. Report it rather than uninstalling.

### 3. Confirm it correctly reports "up to date"

Settings → **Check for updates**.

- It should name the version you have (2.4.0).
- It should say you are on the newest version.
- It must **not** offer 2.4.0 to a phone already running 2.4.0. Being offered the build you
  are already on is a bug — report it rather than installing.

That is the whole of Part A. It verifies the install path and the "no false update" rule; it
does not verify downloading or installing from inside the app.

---

## Part B — once a later build is published

Needs a release with `versionCode` 19 or higher. Nothing below can be done before then.

### 4. The offer

Settings → **Check for updates**. It should name the new version and its download size, and
show a **Download and install** button. Nothing should download until you tap it.

### 5. The install permission

The first time, Android will say it needs permission for PocketPad to install apps. Tap
**Open that setting**, turn on "Allow from this source", and press Back.

**You should come back to the offer, not to a dead end.** The dialog should now show
"Download and install" again, ready for one tap. It must *not* start downloading by itself —
granting a permission is not the same as asking to install.

If you want to check the refusal path instead, leave the switch **off** and tap Download and
install: the app must say it needs permission and offer the setting, not fail silently and not
start downloading.

### 6. Download and install

Tap **Download and install**.

- A progress bar should move and reach 100%.
- Android's own installer should then appear and ask you to confirm. **It must ask** — nothing
  installs on its own.
- Tap Update / Install.

If instead you get "the download arrived cut short", "does not match the release checksum",
"did not publish a usable checksum", or "that file is … not PocketPad", nothing was installed.
That is the verification working; capture the message.

#### Provoking an interrupted download (optional but worth it)

Turn Wi-Fi and mobile data off partway through. The app should report a failure and install
nothing. Turn the network back on and try again — it should succeed. This is the one failure
mode worth causing deliberately, because a half-downloaded APK handed to the installer fails
*there*, with a message that looks like the app is broken rather than like the download was.

### 7. Confirm nothing was lost

Reopen PocketPad and check every item from step 1 again:

- [ ] Controller style is the one you had
- [ ] Custom layout is exactly where you left it (the button you moved is still moved)
- [ ] Background picture is still there
- [ ] Haptics level unchanged
- [ ] TV menu keys setting unchanged
- [ ] **The TV reconnects without re-pairing** — it should appear in the paired list as before
- [ ] Settings → Check for updates now says you are up to date

Then play for a minute: d-pad, both sticks, all four face buttons, both shoulders, both
triggers. If you use the PS3 pad over USB-C, plug it in and confirm it is still picked up.

---

## What a failure means

- **"App not installed"** — the signing key differs between the two builds. Do not uninstall to
  work around it; report it. Both are signed with the repository's committed debug keystore, so
  if this happens the cause is in CI, not on your phone.
- **Settings reset** — the package name changed, which should be impossible in this change.
  Report it with the version you came from.
- **TV asks to pair again** — check whether the release notes mention a HID descriptor change.
  A descriptor change legitimately requires re-pairing; neither 2.4.0 nor this PR contains one.
- **Offered a version you already have** — a version-comparison bug. Report the version the app
  says you have and the version it offered.

## What this procedure does not prove

That every Android version and every TV behaves this way. It proves it on your phone, with your
TV, for one pair of versions — which is the claim worth making, and the only one anyone can
make without a device lab.
