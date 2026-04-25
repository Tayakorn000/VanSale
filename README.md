# VanSale

An Android point-of-sale application for van-based ice delivery operations. Drivers run the device in kiosk mode, create delivery/tax invoices on the fly, print thermal receipts via Bluetooth, and sync every transaction to a Google Sheets backend.

Built with Jetpack Compose, Room, and a coroutine-based sync pipeline that keeps invoice numbers consistent between printed receipts and cloud records — even when the device is offline for hours.

---

## Features

- **Offline-first billing** — orders are written to a local Room database the moment they are created; the cloud is a replica, not a dependency.
- **Captive-portal-aware auto-sync** — background watcher validates real internet (`NET_CAPABILITY_VALIDATED` + `generate_204` probe) before uploading, so half-open Wi-Fi networks never produce false "synced" states.
- **Race-free invoice numbering** — a single `Mutex` serializes the manual print path and the auto-sync loop, and the `invoice_offset` is read inside the lock so printed and uploaded numbers cannot diverge.
- **Bluetooth thermal printing** — delivery notes, tax invoices, and customer summaries via ESC/POS.
- **Kiosk mode** — immersive fullscreen, `startLockTask()`, intent-filter as launcher, back-button gated, admin menu hidden from drivers.
- **Live sync indicator** — modern cloud icon in the top bar reflects the real connectivity state; no placeholder UI.
- **Google Sheets backend** — invoices, customers, employees, and routes round-trip through a single Apps Script endpoint.

---

## Tech stack

| Layer | Technology |
|---|---|
| UI | Jetpack Compose, Material 3 |
| Local storage | Room 2.6 (+ KSP) |
| Concurrency | Kotlin Coroutines, `Mutex` |
| Networking | `HttpURLConnection` (no third-party HTTP client required) |
| Printing | DantSu ESCPOS-ThermalPrinter |
| Backend | Google Apps Script → Google Sheets |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 34 |

---

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                 MainActivity (Compose)               │
│  ┌────────────┐  ┌────────────┐  ┌────────────────┐  │
│  │ BillingScr │  │ AdminScr   │  │ HistoryScr     │  │
│  └─────┬──────┘  └────────────┘  └────────────────┘  │
│        │ order.id                                    │
│        ▼                                             │
│  ┌──────────────────────────────────────────────┐    │
│  │ Room (AppDatabase)                           │    │
│  │   orders · order_items · customers · ...     │    │
│  └───────┬──────────────────────────────────────┘    │
│          │                                           │
│          ▼                                           │
│  ┌──────────────────────────────────────────────┐    │
│  │ syncPendingCloudOrders (guarded by Mutex)    │    │
│  │   1. hasRealInternet()  — captive-portal safe│    │
│  │   2. Read invoice_offset from SharedPrefs    │    │
│  │   3. actualInvoiceNo = order.id + offset     │    │
│  │   4. POST → Apps Script → Google Sheets      │    │
│  │   5. markOrderAsSynced on success            │    │
│  └──────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────┘
```

### Invoice number integrity

Printed invoice numbers and uploaded invoice numbers share a single formula:

```
actualInvoiceNo = order.id + invoice_offset
baseInvoice     = "${routeId}${actualInvoiceNo:07d}"
deliveryNo      = baseInvoice        if customer.print_delivery
taxNo           = "V" + baseInvoice  if customer.print_tax
```

`invoice_offset` lives in `SharedPreferences` and is read **inside** the sync mutex, so the value cannot change between the print path and the upload path for a given order.

---

## Setup

### 1. Clone

```bash
git clone https://github.com/Tayakorn000/VanSale.git
cd VanSale
```

### 2. Configure local secrets

Create or edit `local.properties` at the project root:

```properties
sdk.dir=/path/to/Android/sdk
SCRIPT_URL=https://script.google.com/macros/s/<your-deployment-id>/exec
```

`local.properties` is gitignored. The build reads `SCRIPT_URL` into `BuildConfig.SCRIPT_URL` at compile time.

### 3. Deploy the Apps Script backend

The app expects an Apps Script Web App that responds to:

| Method | Query / Body | Purpose |
|---|---|---|
| `GET` | `?action=getEmployees` | JSON array of `{emp_id, name, role}` |
| `GET` | `?action=getRoutes` | JSON array of route codes |
| `GET` | `?action=getCustomers` | JSON array of customer records |
| `POST` | JSON body (see `uploadToGoogleSheet`) | Append a row to the invoices sheet |

Deploy as a Web App (Execute as: you, Access: anyone) and paste the URL into `local.properties`.

### 4. Build

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release-unsigned.apk`

### 5. Sign (for internal distribution)

```bash
# One-time: generate a release keystore
keytool -genkey -v -keystore vansale-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias vansale

# Sign
$ANDROID_HOME/build-tools/<version>/zipalign -f -p 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  app/build/outputs/apk/release/app-release-aligned.apk

$ANDROID_HOME/build-tools/<version>/apksigner sign \
  --ks vansale-release.jks \
  --out app/build/outputs/apk/release/VanSale-release.apk \
  app/build/outputs/apk/release/app-release-aligned.apk
```

---

## Kiosk mode

The app is designed to run as a dedicated device launcher.

- `startLockTask()` is invoked in `MainActivity1.onCreate`. On consumer devices this shows a one-time "pin this app" confirmation. On fully-managed devices (Device Owner), pinning is silent and unbreakable.
- The intent-filter includes `CATEGORY_HOME`, so the app can be selected as the default launcher.
- Immersive fullscreen hides the status and navigation bars; a transient swipe will reveal them briefly but they auto-hide.
- The system back button is consumed on the billing screen.
- The admin menu — including "Unlock Kiosk" — is only rendered when the logged-in user has role `ADMIN` or uses the master code `9999`.

**To exit kiosk mode for maintenance:** log in with admin code `9999` → top-right menu → *🔓 ปลดล็อก Kiosk*.

---

## Permissions

| Permission | Reason |
|---|---|
| `INTERNET` | Sync to Google Sheets |
| `ACCESS_NETWORK_STATE` | Real-internet / captive-portal detection |
| `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` | Thermal printer discovery & pairing |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Required by Android for BLE scan on API 31+ |
| `RECEIVE_BOOT_COMPLETED` | Re-launch kiosk after reboot |
| `WRITE_EXTERNAL_STORAGE` (API ≤ 28) | Legacy CSV export path |

---

## Project layout

```
app/src/main/
├── AndroidManifest.xml
└── java/com/example/myapplication1/
    ├── MainActivity.kt               # Entry point, screens, sync engine
    ├── BootReceiver.kt               # Auto-start on device boot
    ├── data/db/                      # Room entities + DAOs
    ├── printing/BluetoothPrinter.kt  # ESC/POS driver
    ├── ui/billing/BillingViewModel   # Billing state
    ├── ui/theme/                     # Material 3 theme
    └── utils/CsvExporter.kt          # CD-Organizer CSV export
```

---

## Security notes

- `SCRIPT_URL` is a **sensitive capability URL**. Anyone who has it can call the backend. It must never be committed. Use `local.properties` + `BuildConfig` as shown.
- The Apps Script should validate the `empId` in every POST against a known-employee list to reject forged uploads.
- Devices in the field should be signed into a **company-owned Google account** with Find My Device enabled. This is the primary anti-theft mechanism — a fake GPS indicator in the app is not a substitute.
- Debug-signed APKs are acceptable for internal fleet deployment, but never for Play Store. Create a release keystore and keep it backed up separately.

---

## License

Tayakorn Wetchakun
