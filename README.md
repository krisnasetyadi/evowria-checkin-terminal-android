# Evowria Check-in for Android

An Android app for Evowria check-in operations. It hosts the web check-in flow and adds reliable Bluetooth ESC/POS printing for internal guest labels.

Read the [architecture guide](docs/ARCHITECTURE.md) before changing the code. Installation and APK build steps are available in the [development guide](docs/DEVELOPMENT.md).

## What it does

- Selects an event by setup QR or exact event code, then confirms the couple before opening check-in.
- Exposes `window.EvowriaPrinter` to the loaded web page.
- Connects to an already-paired Bluetooth Classic thermal printer.
- Prints a 58 mm guest label using ESC/POS.

No API keys, customer data, or production URLs are stored in this repository.

## Development status

The source is scaffolded. Building an APK requires Android Studio (JDK 17 and Android SDK) because this workstation does not currently have an Android toolchain installed.

## Use

1. Open the project in Android Studio.
2. Let Android Studio install the requested Android SDK and Gradle dependencies.
3. Build and install the `app` debug variant.
4. In the app, scan the event setup QR or enter its event code, confirm the couple, then pair the thermal printer from Android settings.
5. Use **Connect printer** in the web terminal before printing a test label.

## Web bridge

```ts
window.EvowriaPrinter.getPrinterStatus()
window.EvowriaPrinter.connect()
window.EvowriaPrinter.testPrint()
window.EvowriaPrinter.printGuestLabel({ printJobId, guestName, side, category, pax })
```

Each method resolves to a serializable status object. The web app must treat `UNKNOWN` as requiring a physical check of the printer.

## Compatibility

The initial implementation uses Bluetooth Classic RFCOMM with the common serial-port UUID and ESC/POS commands. Confirm the actual printer protocol before using it in production; BLE-only printers or proprietary protocols require an adapter.

Only enter a terminal URL you trust. The configured web page receives access to the Bluetooth printer through the native bridge.
