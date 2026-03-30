# FlickpayPOS Android App (MVP)

Android app that mirrors the Electron POS approach for USB thermal printers.

## What this build includes

- Landscape-locked POS shell (WebView)
- Foreground local proxy service on port `8070`
- Odoo-compatible endpoints:
  - `GET /hw_proxy/hello`
  - `POST /hw_proxy/handshake`
  - `GET /hw_proxy/status_json`
  - `POST /hw_proxy/default_printer_action`
  - `POST /hw_proxy/default_printer_label_action`
  - `POST /hw_proxy/print_xml_receipt`
- USB ESC/POS printing:
  - Base64 PNG receipt image printing
  - Plain text fallback printing
  - Cash drawer pulse command
- Local HTTPS enabled (bundled PKCS#12 certificate)

## Key behavior

- App stays in landscape (`sensorLandscape`).
- Long-press inside the WebView opens a URL editor so you can change POS URL.
- On startup, app requests USB permissions for attached devices.

## Default URL

`https://app.flickpay.co.uk/pos/ui/1/`

Change it by long-pressing the screen.

## Build

Open in Android Studio and build `app` module.

If you want CLI builds, generate Gradle wrapper from Android Studio (or install Gradle and run `gradle wrapper`).

## Notes

- This is an MVP focused on your Electron-style proxy pattern and USB receipt printing.
- For production hardening, add:
  - richer printer selection UI,
  - retry/queue management,
  - stricter certificate pinning strategy,
  - remote config lock-down,
  - crash telemetry.
