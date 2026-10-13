# Verifying an in-app update on a phone — Galaxy A17

Automated tests cover the decision-making: what counts as a newer version, which URLs may be
fetched, what a release with no APK does, what happens to a download that arrives cut short.
They cannot cover the part that matters most to you — that the update installs over your copy
and your settings survive. That needs the phone.

This is the procedure. It takes about ten minutes and nothing in it is destructive.

## Before you start

You need PocketPad **2.3.0 (versionCode 17)** installed — the build on your phone now — and a
release of **2.4.0 (versionCode 18)** or later published, which happens when this branch is
merged to `main` and CI finishes.

> **The first hop has to be manual.** The copy you are running was built before the updater
> existed, so it has no Check for updates button. Install 2.4.0 once from
> <https://github.com/Dathaze20/PocketPad/releases/latest> the usual way. Every update after
> that one can be done from inside the app, which is the point of all this.

## 1. Record what must survive

Open PocketPad and note, or screenshot:

- the controller style you use (Settings → Controller style)
- whether you have a custom layout saved (drag a button somewhere obvious if you want an
  unmistakable marker, then SAVE)
- your background picture, if you set one
- your haptics level
- whether TV menu keys are on or off
- the device you are paired with, in your TV's Bluetooth list

Pair with the TV and press a few buttons, so you know the link works before you touch anything.

## 2. Check the version it reports

Settings → **Check for updates**.

- It should name the version you have.
- With 2.3.0 installed and 2.4.0 published it should offer **2.4.0**, with a size.
- It must **not** offer a version you already have. If it offers 2.4.0 while you are running
  2.4.0, that is a bug — report it rather than installing.

## 3. The install permission

The first time, Android will say it needs permission for PocketPad to install apps. Tap
**Open that setting**, turn on "Allow from this source", and come back.

If you want to check the refusal path, leave the switch **off** and tap Download and install:
the app must say it needs permission and offer the setting, not fail silently and not start
downloading.

## 4. Download and install

Tap **Download and install**.

- A progress bar should move and reach 100%.
- Android's own installer should then appear and ask you to confirm. **It must ask** — nothing
  should install on its own.
- Tap Update / Install.

If instead you get "the download arrived cut short", "does not match the release checksum", or
"that file is … not PocketPad", nothing was installed. That is the verification working;
capture the message.

### Checking the interrupted-download path (optional)

Turn Wi-Fi and mobile data off partway through the download. The app should report a failure
and install nothing. Turn the network back on and try again — it should succeed. This is the
one failure mode worth provoking deliberately, because a half-downloaded APK handed to the
installer fails there with a message that looks like the app is broken rather than like the
download was.

## 5. Confirm nothing was lost

Reopen PocketPad and check every item from step 1:

- [ ] Controller style is the one you had
- [ ] Custom layout is exactly where you left it (the button you moved is still moved)
- [ ] Background picture is still there
- [ ] Haptics level unchanged
- [ ] TV menu keys setting unchanged
- [ ] **The TV reconnects without re-pairing** — it should appear in the paired list as before.
      You should not have to delete and re-pair it.
- [ ] Settings → Check for updates now says you are up to date

Then play for a minute: d-pad, both sticks, all four face buttons, both shoulders, both
triggers. If you use the PS3 pad over USB-C, plug it in and confirm it is still picked up.

## 6. What a failure here means

- **"App not installed" during step 4** — the signing key differs between the two builds.
  Do not uninstall to work around it; report it. Both builds are signed with the repository's
  committed debug keystore, so this should not happen, and if it does the cause is in CI, not
  on your phone.
- **Settings reset** — the package name changed, which should be impossible in this change.
  Report it with the version you came from.
- **TV asks to pair again** — note whether the HID descriptor changed in the release notes.
  A descriptor change legitimately requires re-pairing; this update does not contain one.

## What this procedure does not prove

That every Android version and every TV behaves this way. It proves it on your phone, with
your TV, for this pair of versions — which is the claim worth making, and the only one anyone
can make without a device lab.
