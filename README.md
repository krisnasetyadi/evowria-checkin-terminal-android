# Evowria Check-in for Android

An Android app for Evowria check-in operations. It hosts the web check-in flow and adds reliable Bluetooth ESC/POS printing for internal guest labels.

Read the [architecture guide](docs/ARCHITECTURE.md) before changing the code. Installation and APK build steps are available in the [development guide](docs/DEVELOPMENT.md).

## What it does

- Selects an event by setup QR, from the events this device opened before (searchable by couple name or code), or by exact event code, then confirms the couple before opening check-in. The server never lists events; the searchable list lives only on the device.
- Exposes `window.EvowriaPrinter` to the loaded web page.
- Connects to an already-paired Bluetooth Classic thermal printer.
- Prints a 58 mm guest label using ESC/POS.

No API keys, customer data, or production URLs are stored in this repository.

## Development status

The source is scaffolded. Building an APK requires Android Studio (JDK 17 and Android SDK) because this workstation does not currently have an Android toolchain installed.

## Two builds: prod and dev

The app has two flavors. They install as separate apps, so both can sit on
one tablet.

| Build | Install this file | App name / icon | Server |
| --- | --- | --- | --- |
| `prodDebug` | `app/build/outputs/apk/prod/debug/app-prod-debug.apk` | "Evowria Check-in", cream icon | `https://www.evowria.com`, HTTPS only |
| `devDebug` | `app/build/outputs/apk/dev/debug/app-dev-debug.apk` | "Evowria Check-in DEV", orange icon | Any server you type, HTTP allowed |

**Event tablets always get the prod APK.** Only prod reuses the old package
name, so it updates the installed app and keeps its selected event.

Build from the command line with `./gradlew assembleProdDebug` or
`./gradlew assembleDevDebug`, or pick the variant in Android Studio's
**Build Variants** panel before **Build > Build APK(s)**.

### Testing against a local server (dev build)

1. Run the web app on the laptop (`npm run dev`, port 3021).
2. Connect the phone with USB debugging and run
   `adb reverse tcp:3021 tcp:3021`.
3. Open the DEV app. The event picker has a server field, which defaults to
   `http://localhost:3021`. Keep it: `localhost` counts as a secure origin,
   so the web QR camera works.
4. Without USB, type the laptop's address instead, for example
   `http://192.168.1.5:3021`. Pages and printing work, but the web camera
   does not, because the WebView only allows the camera on HTTPS or
   localhost. Use search instead of scanning.
5. Inspect the page from the laptop at `chrome://inspect` (enabled in dev
   builds only).

## Use

1. Open the project in Android Studio.
2. Let Android Studio install the requested Android SDK and Gradle dependencies.
3. Build and install the `prodDebug` variant (or `devDebug` for a local server, see above).
4. In the app, scan the event setup QR, pick an event this device opened before, or type its event code, confirm the couple, then pair the thermal printer from Android settings.
5. Use **Connect printer** in the web terminal before printing a test label.

## Web bridge

```ts
window.EvowriaPrinter.getPrinterStatus()
window.EvowriaPrinter.connect()
window.EvowriaPrinter.testPrint()
window.EvowriaPrinter.printGuestLabel({ printJobId, guestCode, guestName, side, category, pax, table })
```

Each method resolves to a serializable status object. The web app must treat `UNKNOWN` as requiring a physical check of the printer.

## Compatibility

The initial implementation uses Bluetooth Classic RFCOMM with the common serial-port UUID and ESC/POS commands. Confirm the actual printer protocol before using it in production; BLE-only printers or proprietary protocols require an adapter.

Only enter a terminal URL you trust. The configured web page receives access to the Bluetooth printer through the native bridge.
