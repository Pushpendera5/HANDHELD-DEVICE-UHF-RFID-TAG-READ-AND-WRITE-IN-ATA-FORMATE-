# OpenRFID-Handheld-Android-Middleware

## 📌 Repository Description

> **Universal open-source Android handheld RFID middleware & suite supporting Chainway, Seuic, Zebra, ATID & Honeywell readers. Features a multi-vendor HAL, real-time tag inventory table, EPC writing, security locking, and audio feedback.**

---

## 📥 Download APK

[![Download APK](https://img.shields.io/badge/Download-Latest%20APK-brightgreen?style=for-the-badge&logo=android)](https://github.com/rfidsoftwares/OpenRFID-Handheld-Android-Middleware/releases/latest/download/app-debug.apk)

You can download the latest pre-compiled Android APK directly to your device:

* 📱 **[Direct Download Latest APK (app-debug.apk)](https://github.com/rfidsoftwares/OpenRFID-Handheld-Android-Middleware/releases/latest/download/app-debug.apk)**
* 📦 **[View All Releases & Assets](https://github.com/rfidsoftwares/OpenRFID-Handheld-Android-Middleware/releases)**

> **Note**: When installing the APK on your handheld device or Android phone, allow *"Install from Unknown Sources"* if prompted by your browser.

---

## 🚀 Key Features

* **Multi-Vendor Hardware Abstraction Layer (HAL)**: Unified driver abstraction supporting all major handheld RFID hardware vendors:
  * **Chainway** (C72, C71, C66) — Native SDK (`cw-deviceapi20191022.jar`)
  * **Seuic** (AUTOID9U, Cruise) — Native SDK (`uhf.jar`, `scankey.jar`)
  * **Zebra** (MC3300R, RFD8500, RFD40, RFD90) — Native SDK (`rfidapi3lib-2.0.5.275.aar`)
  * **ATID** (AT911N, AT870, AT388) — Native SDK (`atid.dev.rfid.jar`)
  * **Honeywell** (CT40, EDA51, IH25) — Native Mobility SDK
  * **Mock Software Reader** — Full deterministic emulator for testing on standard smartphones & Android Studio emulators without physical hardware.
* **Eager Reader Initialization**: Hardware RFID chip is powered on and warmed up asynchronously upon entering screens for instant scan response.
* **Tabular Tag Inventory View**: Real-time updating table showing `# | EPC | RSSI | Count | Time` with support for **Single Shot** and **Continuous Live Scan** modes.
* **Tag Security & Locking**: Dedicated Tag Lock & Unlock screen supporting Access Password authentication and Gen2 memory bank security controls.
* **EPC Rewrite & Data Writing**: Update tag EPC values (HEX or ASCII) and write to USER/RESERVED memory banks.
* **Configurable Audio Beep Sound**: Integrated `SoundBeeper` utilizing system `ToneGenerator` with audio toggle switch in UHF Configuration settings.
* **Auto-Hardware Detection & Dropdown Override**: Automatic detection via `Build.MODEL`/`Build.MANUFACTURER` with explicit driver dropdown override.
* **Corporate Branding**: Native light/dark theme support, custom branded splash screen, and corporate assets from RFID Software India Private Limited.

---

## 🏗️ Architecture Overview

```text
OpenRFID Handheld Android Middleware Architecture
─────────────────────────────────────────────────────────────────────────────
 ┌─────────────────────────────────────────────────────────────────────────┐
 │                   UI Layer (Material 3 Components)                      │
 │  ModeSelector | ReadTag | WriteTag | UpdateEpc | TagLock | UhfConfig    │
 └────────────────────────────────────┬────────────────────────────────────┘
                                      │
 ┌────────────────────────────────────▼────────────────────────────────────┐
 │               Multi-Vendor Hardware Abstraction Layer (HAL)              │
 │                         RfidDriverFactory                               │
 └──────┬──────────────┬──────────────┬──────────────┬──────────────┬──────┘
        │              │              │              │              │
 ┌──────▼──────┐┌──────▼──────┐┌──────▼──────┐┌──────▼──────┐┌──────▼──────┐
 │  Chainway   ││    Seuic    ││    Zebra    ││    ATID     ││  Honeywell  │
 │ C72/C71/C66 ││  AUTOID9U   ││  MC3300R    ││   AT911N    ││    CT40     │
 └─────────────┘└─────────────┘└─────────────┘└─────────────┘└─────────────┘
```

---

## 🛠️ Build & Development Instructions

### Prerequisites
* JDK 17
* Android Studio Iguana / Jellyfish (or Gradle 8.2+)
* Android SDK 34 (Build-Tools 34.0.0)

### Building the Debug APK
```bash
git clone https://github.com/rfidsoftwares/OpenRFID-Handheld-Android-Middleware.git
cd OpenRFID-Handheld-Android-Middleware
.\gradlew.bat assembleDebug
```
Output APK location: `app/build/outputs/apk/debug/app-debug.apk`

### Running Unit Tests
```bash
.\gradlew.bat testDebugUnitTest
```

---

## 🏢 Corporate Contact & Support

**RFID Software India Private Limited**  
* Website: [https://rfidsoftwares.com](https://rfidsoftwares.com)  
* Product: OpenRFID Handheld Android Middleware  
* License: Open Source Middleware for Enterprise RFID Systems
