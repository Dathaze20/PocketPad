# Case Study: PocketPad

*Facts below describe **v2.6.0** (November 2026). Test counts and compatibility will change as testing continues; the [README](README.md) is always current.*

## The problem

I wanted to play games on a Samsung TV without buying a controller. Most "phone as a controller" apps need a companion app or a server on the receiving device and send input over Wi-Fi. A TV cannot run that software.

## The solution

PocketPad makes an Android phone register itself as a standard Bluetooth HID gamepad, using Android's `BluetoothHidDevice` API. The TV, PC or tablet pairs with it exactly as it would with a store-bought controller, so nothing is installed on the receiving device.

It offers PlayStation, Xbox, Super Nintendo, NES and Game Boy layouts, plus an optional Touch Shooter layout. Buttons can be moved and saved per layout, and a controller plugged into the phone over USB can be forwarded through it, including a dedicated driver for the PS3 DualShock 3.

## The engineering challenges

- **Being discovered at all.** Early builds were not offered by the TV. The fix was declaring the HID device class that real controllers use, plus a combined gamepad and keyboard report descriptor (v1.5).
- **Analog triggers and correct button numbering**, so games read the right buttons (v2.2).
- **Input that goes missing under load.** If the Bluetooth stack refused a press and the finger lifted before anything else happened, the release was dropped as a duplicate and the host never saw the press. v2.6.0 rebuilt sending around a queue that keeps button changes in order, merges stick movement and retries refused reports on a budget.
- **Knowing why a host refused.** The app had been discarding the results Bluetooth returns, so a failed connection just said "connecting" forever. Those results are now reported.
- **Safe self-updates.** Downloads are checked against the release's published SHA-256, its package name and its version before Android's installer is opened.

## The development process

I direct the development with AI tools, mainly Claude Code, from my Samsung Galaxy A17, and test on real hardware. The repository history shows the AI involvement openly.

Testing on the TV drove the deeper Bluetooth work. Controls not responding correctly during real play led to the reliability investigation behind v2.6.0, instead of a surface fix. That problem is **not fully resolved for every game**, and it is still being tested. The settings and Touch Shooter work in v2.6.0 also came from using the app, not from a checklist.

## The results

- **Confirmed on hardware:** a Samsung Tizen TV, used for real gameplay.
- **Not yet verified on hardware:** Windows PCs, Android tablets and other hosts, as well as some v2.6.0 controller changes. The README says so plainly.
- 322 automated tests at v2.6.0, run by GitHub Actions on every push and pull request.
- Releases published automatically. CI refuses to publish a build signed with the wrong key, so updates always install over the previous version.

## The lessons

- Hardware integration cannot be proven by a build passing. CI shows the code compiles and its logic holds, but only a real host shows whether it accepts the controller.
- Honest compatibility statements are worth more than broad claims.
- Problems reported from real play often point to a deeper cause than the symptom.

## The next steps

- Verify Windows, tablets and the v2.6.0 controller changes on real devices.
- Finish investigating the remaining in-game control problem.
- Measure input latency. No timing figures are claimed because none have been measured.
- Add real screenshots and a short clip of the phone controlling a game on the TV.
