# PocketPad 🎮

**Turn your Android phone into a real Bluetooth gamepad** for your Smart TV, tablet, another phone, or PC — with on-screen touch controls *and* passthrough for a PS3 controller plugged into the phone with a USB-C OTG adapter.

No app is needed on the other device. PocketPad uses Android's native `BluetoothHidDevice` API to make the phone appear as a standard Bluetooth HID gamepad — exactly like a store-bought controller. Samsung Tizen TVs (like the UN65U7900F) accept standard HID gamepads out of the box, and so does anything else that supports Bluetooth controllers — including the emulators running on those devices (RetroArch and friends see PocketPad as a normal gamepad).

## Features

- **Touch gamepad** — d-pad, △ ○ ✕ □ face buttons, dual analog sticks, L1/L2/R1/R2, L3/R3, Share/Options, and a **central PS/home button** you can press *and hold* just like a real controller. Full multitouch, works in **portrait and landscape**.
- **Controller styles** — switch skins from the ⚙ menu: **PlayStation (PS4/PS5)**, **Super Nintendo** (X/A/B/Y in SNES colors + L/R + Select/Start), **NES**, and **Game Boy**. Same Bluetooth connection, different pad — perfect for retro emulators.
- **Strong haptic feedback** — a crisp vibration click on every press (plus ticks when the d-pad direction changes), so the glass feels like buttons. Three levels: Strong / Light / Off.
- **Connects to anything** — TV, tablet, phone, PC: any Bluetooth HID host. The other device sees a genuine gamepad.
- **PS3 controller passthrough** — plug a DualShock 3 into the phone via a USB-C OTG adapter and its buttons/sticks are forwarded over Bluetooth. Touch and physical controls work at the same time.
- **Any Android-recognized controller works too** — controllers that Android exposes as native gamepads (many SNES-style USB pads, Xbox pads, etc.) are forwarded automatically via key/motion events.
- **Reconnects fast** — remembers the last device and reconnects on launch.

## Requirements

- **Phone:** Android 9 (Pie) or newer with Bluetooth. Tested target: Samsung Galaxy A16 5G (One UI, Android 14+/16). *Note:* the phone's Bluetooth stack must support the HID Device profile — virtually all Samsung Galaxy phones do.
- **TV:** any TV/host that accepts Bluetooth HID gamepads. Samsung Tizen Smart TVs (2018+) do, including the U7900F series.
- **For PS3 passthrough:** a USB-C OTG adapter/cable and a DualShock 3.

## Pairing with your Samsung TV

1. Open PocketPad, allow the Bluetooth permissions, tap the **⚙ gear** → **Make phone discoverable**.
2. On the TV: **Settings → Connection → External Device Manager → Input Device Manager → Bluetooth Device List** (on some models: **Settings → Sound → Bluetooth Device List**).
3. Select the device named after your phone and confirm pairing on both screens.
4. Done — the status pill at the top shows **Connected**. Next time, use **⚙ → Connect to a paired device** to reconnect instantly.

## First-run test plan

Compilation is verified by CI on every push, but Bluetooth pairing can only be proven on real hardware. First time out, check these in order:

1. Open the app → allow Bluetooth permissions → status pill should read **"Gamepad ready — waiting for TV"**. If it says Bluetooth is off, turn it on and reopen.
2. Pair from the TV (steps below) → pill turns **"Connected to …"**.
3. Open a game or the TV's Gaming Hub and press buttons. If the TV pairs but ignores input, try un-pairing and re-pairing from the TV's *Input Device Manager* list specifically (not the audio device list).
4. For emulators: open the emulator's controller settings and map buttons — PocketPad appears as a standard gamepad.

If any step fails, note exactly what the status pill says — that pinpoints where it broke.

## Using the PS3 controller

1. Plug the DualShock 3 into your phone with a USB-C OTG adapter.
2. Android will offer to open PocketPad automatically (the app registers for the DS3's USB vendor/product ID). Grant USB access if asked.
3. Play. The DS3's inputs are merged with the touch controls — whichever is actively pressed wins.

> The DS3 is quirky over USB: it stays silent until it receives a special HID "enable" command. PocketPad sends that automatically. On phones whose kernel already recognizes the DS3 as a gamepad, input arrives through Android's normal gamepad events instead — both paths are handled.

## Project layout

```
app/src/main/java/com/dathaze/pocketpad/
├── MainActivity.kt          # UI glue, permissions, physical-controller input, merging
├── hid/
│   ├── HidConstants.kt      # HID gamepad report descriptor + button mapping
│   ├── GamepadState.kt      # 7-byte input report model + touch/USB merge logic
│   └── HidGamepadManager.kt # BluetoothHidDevice registration, connect, sendReport
├── usb/
│   └── Ds3UsbDriver.kt      # Raw USB host driver for the DualShock 3 (OTG)
└── ui/
    ├── ControllerView.kt    # Multitouch on-screen gamepad, 4 skins (portrait + landscape)
    └── Haptics.kt           # Vibration feedback engine (strong/light/off)
```

## Building

Open the project in Android Studio (Ladybug or newer) and press Run, or:

```bash
./gradlew assembleDebug     # debug APK → app/build/outputs/apk/debug/
./gradlew bundleRelease     # Play Store bundle (configure signing first)
```

See **[docs/PLAY_STORE_CHECKLIST.md](docs/PLAY_STORE_CHECKLIST.md)** for the step-by-step guide to publishing on Google Play (signing, listing, data-safety answers, etc.).

## Troubleshooting

- **TV pairs but nothing happens:** make sure you paired from the TV's *Bluetooth Device List / Input Device Manager*, not the audio-device menu.
- **"Bluetooth is off"**: enable Bluetooth, then reopen the app.
- **DS3 not detected:** try a powered OTG adapter (the DS3 draws bus power), and check the cable supports data, not just charging.
- **Game doesn't react on the TV:** not every Tizen app accepts gamepads — try the TV's Gaming Hub or an app that explicitly supports controllers.
- **Latency:** keep the phone within a few meters of the TV; Bluetooth input latency is typically 15–40 ms.

## License

[MIT](LICENSE)
