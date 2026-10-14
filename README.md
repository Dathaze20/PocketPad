# PocketPad 🎮

**PocketPad turns an Android phone into a real Bluetooth HID gamepad.**

The phone registers itself with Android's native `BluetoothHidDevice` API as a Bluetooth HID peripheral, so a TV, tablet, another phone, a PC — anything that accepts a Bluetooth controller — pairs with it the same way it would pair with a store-bought gamepad.

**Nothing has to be installed on the receiving device.** There is no companion app, no pairing server, and no proprietary network protocol: input travels over the Bluetooth HID link and, optionally, over USB from a controller plugged into the phone.

The app does hold the `INTERNET` permission, for one thing only — **Settings → Check for updates**, which asks GitHub whether a newer release exists and, if you say yes, downloads the APK. Nothing else in the app reaches the network, the check runs only when you tap it, and no button press, layout, background picture or setting is ever uploaded. See [Updating](#updating) and [docs/PRIVACY_POLICY.md](docs/PRIVACY_POLICY.md).

That is the main thing separating PocketPad from most "phone as controller" projects, which stream input to a helper application or a desktop server over Wi-Fi. PocketPad speaks the standard the host already understands.

---

## Contents

- [Install](#install)
- [Updating](#updating)
- [Features](#features)
- [Pairing](#pairing)
- [Physical controller passthrough](#physical-controller-passthrough)
- [How it works](#how-it-works)
- [Architecture](#architecture)
- [Building](#building)
- [Compatibility and known limitations](#compatibility-and-known-limitations)
- [Screenshots / demo](#screenshots--demo)
- [Docs](#docs)
- [License](#license)

---

## Install

### Option 1 — download the APK (easiest)

Every push to `main` is built by GitHub Actions and published to the repository's **`latest`** release:

**[→ Download PocketPad.apk](https://github.com/Dathaze20/PocketPad/releases/latest)**

No GitHub login is needed. Tap the APK under **Assets**, then open the download to install.

Because the APK is sideloaded, Android shows a Google Play Protect warning for an app it has not seen before. On the **"App blocked to protect your device"** dialog choose **More details → Install anyway**. Every warning screen, and what to do about the "App not installed" case, is documented in **[docs/INSTALL.md](docs/INSTALL.md)**.

> Builds are signed with the repository's committed debug keystore. That is deliberate: a stable signature means each new build installs as an update over the previous one instead of being rejected. It is a test-signing key only — a Play Store release would use the separate release keystore described in [`app/build.gradle`](app/build.gradle).

### Option 2 — build it yourself

See [Building](#building). Building from source produces the same app; it is the right path if you want to change the code or sign it yourself.

---

## Updating

**Settings → Check for updates.** No GitHub page, no browser, no re-download by hand.

Tapping it asks GitHub whether the published release is newer than what you have, and says so either way. If it is, you get the version number and the download size, and nothing happens until you tap **Download and install**. Android's own installer then asks you to confirm — PocketPad cannot install anything silently, and the first time you use it Android will ask you to allow installs from PocketPad, which is a one-time switch it offers to open for you.

Updating keeps everything: pairings, custom layouts, controller style, background picture, haptics and every other setting. The app's package name and signing key are unchanged, so Android installs the new APK over the old one without an uninstall.

**What makes a build appear as an update.** Android decides what counts as an upgrade by `versionCode` alone, and PocketPad's `versionCode` is set by hand in [`app/build.gradle`](app/build.gradle) — it is not derived from the version name (2.3.0 is `versionCode` 17, not 20300). **Bump `versionCode` to ship a build to phones that already have PocketPad.**

A push to `main` whose `versionCode` is not higher than what is already published **leaves the release alone**: the existing APK, its download link and its `version.json` all stay exactly as they are, and CI prints a warning saying so. Only a higher `versionCode` replaces them, and it does so by uploading over the existing release rather than deleting and recreating it — the `latest` tag and the download URL above are never taken away, even briefly. CI then re-downloads that URL and refuses to finish unless it serves the build that was just made.

So that the app has a number it can trust, each release carries a small `version.json` beside `PocketPad.apk`, written by CI from the APK it just built (`aapt2 dump badging`) and holding its `versionCode`, `versionName` and SHA-256. The APK keeps its name, so the download link above is unchanged.

**What is checked before anything is installed:**

- the download starts from this repository's own release-download URL on `github.com` — not merely "somewhere on GitHub", which would include every other repository on the site — and every redirect hop stays on GitHub over HTTPS;
- it matches the length **and the SHA-256** the release published. The checksum is required: a release that does not publish a usable one is refused rather than verified by length alone, because length is the one property of a substituted file that is free to match;
- it reads back through Android as `com.dathaze.pocketpad`, at the version that was offered, and newer than the one running.

Anything else is refused with a message, and the file is deleted.

CI also refuses to publish an APK that is not signed with the repository's committed debug keystore, because an APK signed with a different key cannot install over an existing copy — Android would make you uninstall first, losing your layouts and pairings.

Releases published before this existed carry no `version.json`; the app says so plainly rather than guessing, and the next build fixes it.

**Your first 2.4.0 install has to be by hand.** The build before it has no Check for updates button, so nothing in it can fetch one. Download the APK once from the link above; updates after that come from inside the app. [docs/UPDATE_TESTING.md](docs/UPDATE_TESTING.md) has the full procedure.

---

## Features

### Touch controller

| | |
|---|---|
| **Controls** | 8-way d-pad, four face buttons, dual analog sticks, four shoulder buttons/triggers, two stick-click buttons, Select/Start-style pair, and a central Home button |
| **Multitouch** | Every control is hit-tested independently, so several fingers work at once |
| **Orientation** | Separate portrait and landscape layouts for each style |
| **Rendering** | Drawn directly to a `Canvas` — shaded caps with sockets, bevels and press states, a one-piece pivoting d-pad, and domed thumbsticks |
| **Haptics** | Per-press vibration with **Strong / Light / Off** levels, using the platform's predefined click and tick effects where available |
| **Safe area** | Controls and the status pill lay out inside the display cutout and system-bar insets |

### Controller styles

Five styles, switchable from the ⚙ menu. Every style drives the **same** HID report — face buttons are mapped positionally (bottom / right / left / top), which is the order hosts read regardless of the labels printed on a pad. Switching style changes what you see, never what the host receives.

| Style | Labels |
|---|---|
| **PlayStation (PS4 / PS5)** | △ ○ ✕ □, L1/L2/R1/R2, L3/R3, SHARE / OPTIONS / PS |
| **Xbox** | A / B / X / Y, LB/LT/RB/RT, LS/RS, VIEW / MENU — with the offset Xbox stick-and-d-pad arrangement |
| **Super Nintendo** | X / A / B / Y in SNES colours, L / R, SELECT / START |
| **NES** | B / A, SELECT / START |
| **Game Boy** | B / A on the diagonal, SELECT / START |

### Customisation

- **Move buttons** — drag any control anywhere on screen, then **Save**. Layouts are stored **per style and per orientation**, so a custom landscape PlayStation layout does not disturb the portrait one. **Default** restores the built-in layout; **Cancel** discards edits.
- **Background picture** — pick any image through Android's system photo picker (no storage permission required). The image is copied into app storage, downsampled to roughly screen resolution before decoding, then drawn *beneath* the controls with a dark scrim between, so no photo can obscure a button. Drag to frame it; the framing is remembered.
- **Vibration level** — Strong / Light / Off.
- **TV menu keys** — an opt-in mode, **off by default**, in which the pad additionally sends keyboard keys (arrows, Enter, Escape) so it can drive a TV's menus. It is off by default because while a game is running those keys are duplicate input, and Escape is read as "quit" by some clients.

### Connection

- **Long-press Home** to enter pairing mode without opening a menu.
- A **status ring** around the Home button shows link state: dim when idle, pulsing while the phone is discoverable, solid green when connected. A status pill at the top of the screen shows the same in words.
- **Reconnect** — the last connected device's address is saved and reconnected on launch.
- **Paired-device picker** — reconnect to any bonded device from a list.

---

## Pairing

PocketPad is the *peripheral*. Like any controller, the host discovers it — you do not connect outward from the phone.

1. In PocketPad, **long-press the Home button** (or ⚙ → *Make phone discoverable*) and accept Android's prompt to make the phone visible.
2. On the host, open its Bluetooth **input device** list and select your phone.
   On a Samsung Tizen TV that is **Settings → Connection → External Device Manager → Input Device Manager → Bluetooth Device List**. Use the input-device list, not the audio/sound Bluetooth list — that one is for speakers and headphones.
3. Confirm on both screens. The status pill reads **Connected**.

Afterwards, ⚙ → *Connect to a paired device* reconnects without re-pairing.

---

## Physical controller passthrough

PocketPad can forward a controller plugged into the phone, so the phone acts as a bridge to the host. Touch input and physical input are merged, not switched between — buttons are OR-ed together, the hat and each stick take whichever source is actually deflected, and each trigger takes the larger of the two values.

### DualShock 3 (PS3) over USB OTG

A dedicated USB-host driver handles the DualShock 3 (`0x054C` / `0x0268`) directly:

- Claims the controller's HID interface and locates its interrupt IN endpoint.
- Sends the DS3's **"enable operational mode"** control transfer (`SET_REPORT`, feature report `0xF4`, payload `42 0C 00 00`). Without this the DS3 stays silent over USB — it reports nothing until asked.
- Parses the input report: digital buttons, the d-pad, both sticks, and the DS3's **analog trigger pressure** (report bytes 18–19), falling back to digital values on short reports.
- Requests USB permission when needed, and releases the interface immediately on a detach broadcast rather than waiting for read timeouts.
- The app registers the DS3's vendor/product ID in its manifest, so Android can offer to launch PocketPad when the controller is plugged in.

> Some Android builds bind the DS3 with the kernel's own driver and expose it as an ordinary gamepad. In that case the raw driver is never opened and input arrives through the path below instead. Both paths are implemented.

### Other Android-recognised controllers

Controllers that Android exposes as native gamepads are forwarded through `KeyEvent` and `MotionEvent`: face buttons, bumpers, triggers, stick clicks, Select/Start/Mode, both sticks, and the hat axes. Analog trigger values are read from the platform's trigger/brake/gas axes and forwarded as analog values rather than being flattened to on/off.

---

## How it works

The phone takes the **HID Device (peripheral)** role through `BluetoothHidDevice`, which Android has exposed since API 28. The host sees a standard HID controller and needs no software of its own.

**Registration and SDP.** On start the app acquires the `HID_DEVICE` profile proxy and registers an SDP record carrying the device name, description, provider, a device subclass, and the HID report descriptor. The subclass advertised is `SUBCLASS1_COMBO` — the class off-the-shelf controllers and remotes report. This matters in practice: hosts filter their input-device lists by that byte, and some firmware skips a device that does not present a class it recognises.

**Report descriptor.** A single composite descriptor declares two top-level collections:

| Report | Contents | Size |
|---|---|---|
| **ID 1 — Gamepad** | 16 buttons; 8-way hat switch with null state; four 8-bit axes (X, Y, Z, Rz); two 8-bit analog triggers declared as **Brake** and **Accelerator** on the Simulation Controls page | 9 bytes |
| **ID 2 — Keyboard** | Standard boot-style keyboard: modifier byte, reserved byte, six key slots | 8 bytes |

Thirteen buttons are mapped in the standard gamepad order — A/B/X/Y, bumpers, triggers, View, Menu, both stick clicks, then Guide. Analog triggers exist because a digital button is not enough for anything that reads a throttle.

The keyboard collection serves two purposes: it lets the pad drive a host's menus as arrow keys when the user opts in, and it gives hosts that ignore a bare gamepad a familiar profile to enumerate.

**Quality of service.** Registration requests a low-latency QoS profile (best-effort service with a small token bucket and a tight latency budget) rather than accepting the default.

**Report transmission.** Input reports are sent on state change only — an identical back-to-back report is dropped, for both the gamepad and keyboard collections, so the radio carries changes rather than a constant stream. Presses are transmitted *before* haptics fire, since the vibrator call does not return instantly. When the host polls with a Get_Report the app replies with a neutral report so the host never stalls, and Set_Report is acknowledged.

**Connection management.** The profile is exclusive: another app taking the HID Device role knocks PocketPad out. When registration is reported lost, the app schedules a backed-off re-registration (up to a bounded number of attempts) instead of stranding the user with a dead pad, and returning to the app also triggers recovery. Disconnects clear the cached report state so the next connection starts clean.

**Lifecycle and permissions.** `BLUETOOTH_CONNECT` and `BLUETOOTH_ADVERTISE` are requested at runtime on Android 12+; the legacy Bluetooth permissions are capped at API 30 in the manifest. Every permission-guarded Bluetooth call is wrapped, so a revoked permission surfaces as a status message rather than a crash. Bluetooth being off at launch is recoverable — the app retries when you return to it. The discoverability prompt's result is checked, so the pairing indicator reflects what the system actually granted rather than assuming the user accepted.

**Input integrity.** Android reuses pointer IDs, so a control still holding an incoming ID is released before the ID is reassigned; the last finger lifting releases everything; and losing window focus mid-press (notification shade, incoming call) releases held controls. Without these a button could stay transmitted as held indefinitely.

---

## Architecture

```
app/src/main/java/com/dathaze/pocketpad/
├── MainActivity.kt            # Wiring: settings menu, permissions, photo picker,
│                              # native-controller events, touch/physical merge
├── hid/
│   ├── HidConstants.kt        # Composite HID report descriptor, button map, key codes
│   ├── GamepadState.kt        # 9-byte report model + merge rules
│   └── HidGamepadManager.kt   # BluetoothHidDevice: registration, SDP, QoS, connection
│                              # lifecycle, re-registration recovery, report sending
├── usb/
│   └── Ds3UsbDriver.kt        # USB-host driver for the DualShock 3
├── update/
│   ├── UpdateVersion.kt       # What a release is, and what counts as newer
│   ├── UpdateAssets.kt        # Which URLs and which asset may be fetched
│   ├── ReleaseMetadata.kt     # Reads GitHub's release JSON and version.json
│   ├── DownloadCheck.kt       # Length, checksum and package-identity verdicts
│   ├── AppUpdater.kt          # HTTPS fetch, download, hand-off to the installer
│   └── UpdateFlow.kt          # The Check-for-updates dialog and its thread
└── ui/
    ├── ControllerView.kt      # Canvas gamepad: 5 styles, layout editor, backgrounds
    └── Haptics.kt             # Vibration levels
```

The `update` package is the only part of the app that touches the network, and it does so
only from the Settings menu. Everything in it except `AppUpdater` and `UpdateFlow` is free of
`android.*`, which is what lets the version comparison, the URL rules and the download checks
be unit-tested directly rather than inferred from a download that happened to work.

Input flows one way:

```
touch  ─┐
        ├─→ merge ─→ GamepadState ─→ 9-byte report ─→ BluetoothHidDevice ─→ host
USB/native pad ─┘
```

---

## Building

Requires JDK 17. The project builds with Android Gradle Plugin 8.13.0, Kotlin 2.2.20 and Gradle 8.14.3 (via the wrapper); `compileSdk`/`targetSdk` are 36 and `minSdk` is 28.

```bash
./gradlew assembleDebug     # debug APK  → app/build/outputs/apk/debug/
./gradlew bundleRelease     # release AAB → app/build/outputs/bundle/release/
```

Release builds are minified and resource-shrunk. They are signed only if you supply `POCKETPAD_STORE_FILE`, `POCKETPAD_STORE_PASSWORD`, `POCKETPAD_KEY_ALIAS` and `POCKETPAD_KEY_PASSWORD` in `~/.gradle/gradle.properties` (never in the repository); otherwise the release bundle is produced unsigned.

**Continuous integration.** [`.github/workflows/android.yml`](.github/workflows/android.yml) runs on every push and pull request: it builds the debug APK and the release bundle, uploads both as workflow artifacts, and — on `main` — republishes the APK to the `latest` GitHub release.

Publishing to Google Play is documented step by step in **[docs/PLAY_STORE_CHECKLIST.md](docs/PLAY_STORE_CHECKLIST.md)**.

---

## Compatibility and known limitations

**Requirements**

- **Android 9 (API 28) or newer.** `BluetoothHidDevice` does not exist before that, which sets the floor.
- A phone whose Bluetooth stack actually implements the **HID Device** profile. The API is standard, but support is ultimately up to the OEM's stack, so this is worth confirming on an unfamiliar device.
- A host that accepts standard Bluetooth HID controllers.
- USB passthrough additionally needs a **data-capable** USB-C OTG adapter; charge-only cables will not work, and the DualShock 3 draws bus power.

**Limitations to be aware of**

- **Pair from the host's input-device list.** Selecting the phone from an audio/Bluetooth-speaker menu attempts the wrong kind of connection.
- **Hosts cache a controller's capabilities at pairing time.** After an update that changes the HID descriptor — analog triggers were added in 2.2 — an already-paired host must be un-paired and paired again before it sees the new capabilities.
- **The Bluetooth HID Device role is exclusive.** Another app claiming it will drop PocketPad's registration; the app re-registers automatically, but only one HID-device app can hold the role at a time.
- **Not every host application accepts gamepad input**, even when the pairing itself succeeds.
- **TV menu keys are off by default** and should stay off while playing: with them on, ✕/A also sends Enter and ○/B also sends Escape, which some clients read as a request to quit.
- **Sideloaded installs trigger Play Protect**, and builds signed with a different key than an existing install cannot replace it — see [docs/INSTALL.md](docs/INSTALL.md).
- **Bluetooth input latency depends on the host, the radio environment and distance.** No timing figures are claimed here because none have been measured for this project.
- Hardware behaviour — pairing, per-game input handling, USB OTG — can only be confirmed on real devices. Continuous integration proves the project compiles, not that a given TV will accept it.

**Privacy.** The app collects nothing. Its only network use is the update check you start yourself. See [docs/PRIVACY_POLICY.md](docs/PRIVACY_POLICY.md).

---

## Screenshots / demo

> **None are committed to this repository yet.** No image files are tracked, so rather than link to files that do not exist, here are the three visuals worth capturing — in priority order.

1. **The landscape controller UI on the phone.** The single most convincing image: the shaded caps, one-piece d-pad and domed sticks are the app's most distinctive visual work and are invisible in a text description. Capture in the PlayStation style, landscape, with the status pill reading *Connected*.
2. **The phone controlling a real host.** A short clip or photo of the phone in hand with a game responding on the TV. This is the claim everything else rests on — that a host accepts it as an ordinary controller — and it is the one thing a screenshot of the app alone cannot demonstrate.
3. **Either the style selector or the customisation in use.** The five styles side by side (PlayStation / Xbox / SNES / NES / Game Boy) shows breadth in one frame; alternatively the layout editor mid-drag, or a DualShock 3 plugged into the phone over OTG with both inputs live, shows the engineering that is otherwise hidden.

A short screen recording of pairing — long-press Home, ring pulses, host connects, ring turns green — would also carry more than a still image, if you record only one thing.

---

## Docs

| Document | Contents |
|---|---|
| [docs/INSTALL.md](docs/INSTALL.md) | Sideloading: Play Protect, signature mismatches, unknown-sources permission, updating from inside the app |
| [docs/UPDATE_TESTING.md](docs/UPDATE_TESTING.md) | On-device procedure for verifying an update keeps your settings |
| [docs/PLAY_STORE_CHECKLIST.md](docs/PLAY_STORE_CHECKLIST.md) | Signing, store listing, data-safety answers, rollout |
| [docs/PRIVACY_POLICY.md](docs/PRIVACY_POLICY.md) | Ready-to-publish policy — no data collected; what the update check does and does not send |

---

## License

[MIT](LICENSE)
