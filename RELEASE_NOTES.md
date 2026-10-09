# Release Notes: OpenRFID Handheld Android Middleware v1.0.0


---

## 🌟 Overview

We are thrilled to announce the initial official open-source release of **OpenRFID Handheld Android Middleware (v1.0.0)**. This release provides a universal, enterprise-grade Android suite and Hardware Abstraction Layer (HAL) designed to seamlessly integrate multi-vendor handheld RFID readers into standard Android applications.

---

## ✨ Key Features & Enhancements

### 1. 🔌 Universal Multi-Vendor Hardware Abstraction Layer (HAL)
* **Unified Driver Abstraction**: Single API interface (`IRfidDriver` & `RfidDriverFactory`) supporting major handheld RFID vendors out of the box:
  * **Chainway** (C72, C71, C66) — Integrated native `cw-deviceapi` SDK.
  * **Seuic** (AUTOID9U, Cruise) — Integrated native `uhf.jar` and `scankey.jar` SDKs.
  * **Zebra** (MC3300R, RFD8500, RFD40, RFD90) — Integrated native `rfidapi3lib` SDK.
  * **ATID** (AT911N, AT870, AT388) — Integrated native `atid.dev.rfid` SDK.
  * **Honeywell** (CT40, EDA51, IH25) — Integrated native Honeywell Mobility SDK.
  * **Mock Software Reader** — Full deterministic emulator for running & testing on standard smartphones & Android Studio emulators without physical RFID hardware.
* **Auto-Hardware Detection**: Automatically detects hardware platform using `Build.MODEL` and `Build.MANUFACTURER` with explicit manual override via UI dropdown.
* **Eager Reader Initialization**: Hardware chip is powered on and warmed up asynchronously upon screen entry for sub-millisecond initial scan response times.

### 2. 📡 Real-Time Tag Inventory & Tabular View
* **Tabular Inventory Grid**: Real-time updating table (`# | EPC | RSSI | Count | Time`) with high-performance list rendering.
* **Dual Scan Modes**:
  * **Single Shot Mode**: Captures immediate single tag scans on trigger press.
  * **Continuous Live Scan Mode**: Sustained high-speed tag inventory collecting hundreds of tags/sec.
* **Audio Scan Feedback**: Integrated `SoundBeeper` using system `ToneGenerator` with sound toggle switch in UHF Configuration.

### 3. ✍️ EPC Rewrite & Memory Bank Writing
* **EPC Rewrite Screen**: Write custom EPC strings to tags in both **HEX** and **ASCII** text formats.
* **Memory Bank Data Writing**: Write arbitrary payload data to `USER`, `RESERVED`, `EPC`, and `TID` memory banks.

### 4. 🔒 Gen2 Tag Security & Memory Locking
* **Memory Bank Locking**: Secure Gen2 RFID tags with `Lock` and `Unlock` actions across EPC, USER, TID, and Reserved memory banks.
* **Access Password Validation**: Requires 8-digit HEX access password for secure execution.

### 5. 🎨 Modern Material 3 UI & Corporate Branding
* **Light / Dark Mode Support**: Dynamic theme switching across all screens with native Material 3 color palette.
* **Corporate Splash Screen**: Branded splash screen and asset suite for RFID Software India Private Limited.

---

## 📱 Supported Devices

| Vendor | Supported Models | Native SDK Version |
| :--- | :--- | :--- |
| **Chainway** | C72, C71, C66 | `cw-deviceapi20191022.jar` |
| **Seuic** | AUTOID9U, Cruise UHF | `uhf.jar`, `scankey.jar` |
| **Zebra** | MC3300R, RFD8500, RFD40, RFD90 | `rfidapi3lib-2.0.5.275.aar` |
| **ATID** | AT911N, AT870, AT388 | `atid.dev.rfid.jar` |
| **Honeywell** | CT40, EDA51, IH25 | Honeywell Mobility SDK |
| **Emulator** | Any Android Phone / AVD | Built-in Mock Driver |

---

## 📦 Downloads & Verification

* **Build Command**:
  ```bash
  .\gradlew.bat assembleDebug
  ```

---

## 📄 License & Attribution

Copyright (c) 2026 **RFID Software India Private Limited**.  
Licensed under the Open Source License. For enterprise inquiries, visit [https://rfidsoftwares.com](https://rfidsoftwares.com).
