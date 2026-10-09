# Handheld UHF RFID Tag Read and Write in ATA Spec 2000 Format

[![Platform](https://img.shields.io/badge/Platform-Android%208.0+-green.svg?style=flat&logo=android)](https://www.android.com/)
[![Language](https://img.shields.io/badge/Language-Kotlin-blue.svg?style=flat&logo=kotlin)](https://kotlinlang.org/)
[![RFID Standard](https://img.shields.io/badge/Standard-ATA%20Spec%202000%20Ch%209-orange.svg)](https://www.ataebiz.org/)
[![Syntax](https://img.shields.io/badge/Syntax-ISO%2FIEC%2015434%20Format%2012-purple.svg)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

An enterprise-ready Android handheld application and middleware for **reading, writing, encoding, and verifying UHF RFID tags in ATA Spec 2000 Format (ISO/IEC 15434 Format 12)**. Designed for aerospace, aviation maintenance, defense, and industrial asset tracking across multi-vendor handheld RFID terminals (Chainway, Zebra, Seuic, ATID, Honeywell).

---

## 📌 Overview

**ATA Spec 2000 Chapter 9** defines the global aerospace standard for Automated Identification and Data Capture (AIDC), specifically for serialized component tracking using high-memory and standard UHF Gen2 RFID tags.

This application provides an intuitive mobile interface and high-performance middleware to:
1. **Encode & Write** complete 12-field ATA Spec 2000 Birth Records to RFID User Memory and EPC banks.
2. **Read & Parse** ATA Spec 2000 compliant tags into human-readable part information.
3. **Control Multi-Vendor RFID Hardware** through a unified Hardware Abstraction Layer (HAL).
4. **Perform standard RFID operations**: High-speed tag inventory, EPC rewrites, memory bank security locking, and RF power configuration.

---

## ✈️ ATA Spec 2000 & ISO/IEC 15434 Support

The application embeds an **AtaSpec2000Encoder** engine that strictly adheres to the **ISO/IEC 15434 Syntax Envelope (Format 12)** using standard Text Element Identifiers (TEIs):

* **Envelope Structure**: `[)>` + `[RS]` + `12` + `[GS]` + *Data Fields* + `[RS]` + `[EOT]`
* **Full 12-Field ATA Birth Record Support**:
  | TEI Code | Field Name | Description | Example |
  | :--- | :--- | :--- | :--- |
  | **MFR / SPL** | CAGE / Supplier Code | Manufacturer or Supplier CAGE Code | `1A2B3` |
  | **PNO** | Part Number | Manufacturer Part Number (PN) | `PN-98745-A` |
  | **SEQ** | Serial Number | Unique Serial Number (SER) | `SN-002914` |
  | **DMF** | Date of Manufacture | Manufacture Date (`YYYYMMDD`) | `20240315` |
  | **EXP** | Expiration Date | Shelf Life / Expiry Date (`YYYYMMDD`) | `20290315` |
  | **QTY** | Quantity | Quantity of parts | `1` |
  | **UNT** | Unit of Measure | Unit code (EA, FT, KG, etc.) | `EA` |
  | **LOT** | Lot Number | Production batch or lot code | `LOT-44B` |
  | **PDN** | Description | Part description string | `TURBINE VALVE` |
  | **CND** | Condition Code | Material condition (`NE`, `SV`, `RP`) | `NE` (New) |
  | **CCR** | Certification Reference | Airworthiness / compliance certificate | `FAA-8130-3` |
  | **TRC** | Traceability | Traceability code or reference | `TRC-77881` |

* **Dual Memory Bank Output**:
  * **User Memory**: Encodes full ISO/IEC 15434 Format 12 syntax payload into hexadecimal bytes.
  * **EPC Bank**: Generates a suggested compact EPC containing the CAGE code, Part Number, and Serial Number for instant scanning in logistics tunnels and gates.

---

## 🚀 Key Features

* 🏷️ **ATA Tag Read & Decode**:
  * Scan any UHF RFID tag; if it contains ATA Spec 2000 data, the app automatically unpacks the envelope and displays all 12 metadata fields.
  * Displays raw envelope, hex stream, and parsed values.

* ✍️ **ATA Tag Write & Verification**:
  * Interactive form to fill CAGE, Part Number, Serial Number, dates, condition, and traceability.
  * Live envelope preview with control character tokens (`[RS]`, `[GS]`, `[EOT]`).
  * One-click write to User Memory and EPC bank with immediate read-after-write verification.

* 🔌 **Multi-Vendor Hardware Abstraction Layer (HAL)**:
  * Single unified API (`IRfidDriver` & `RfidDriverFactory`) supporting major handheld RFID manufacturers:
    * **Chainway**: C72, C71, C66 (Native SDK: `cw-deviceapi20191022.jar`)
    * **Zebra**: MC3300R, RFD8500, RFD40, RFD90 (Native SDK: `rfidapi3lib`)
    * **Seuic**: AUTOID9U, Cruise UHF (Native SDK: `uhf.jar`, `scankey.jar`)
    * **ATID**: AT911N, AT870, AT388 (Native SDK: `atid.dev.rfid.jar`)
    * **Honeywell**: CT40, EDA51, IH25 (Honeywell Mobility SDK)
    * **Mock Software Reader**: Built-in deterministic software simulator for running and debugging on standard Android phones and Android Studio emulators without physical hardware.

* 📡 **High-Speed Inventory Scan**:
  * Real-time tabular view showing `# | EPC | RSSI | Count | Time`.
  * Single-shot and continuous live scan modes with audible beep tone feedback.

* 🔒 **Tag Security & Lock Management**:
  * Lock, Unlock, or Perma-Lock Gen2 memory banks (EPC, TID, User, Reserved) using 32-bit Access Passwords.

* ⚡ **UHF Reader Configuration**:
  * Adjust RF power output (dBm), antenna session (S0-S3), inventory algorithm, and audio beeper toggle.

---

## 🏗️ System Architecture

```text
┌────────────────────────────────────────────────────────────────────────┐
│                   Android UI Layer (Material 3)                        │
│   ReadTagFragment  |  WriteTagFragment  |  UhfConfig  |  TagLock       │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                  ATA Spec 2000 Engine (ISO/IEC 15434)                  │
│       AtaSpec2000Encoder: Full 12-Field Encode / Decode & TEI Parser   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│              Multi-Vendor Hardware Abstraction Layer (HAL)             │
│                            RfidDriverFactory                           │
└───────┬──────────────┬──────────────┬──────────────┬───────────┬───────┘
        │              │              │              │           │
 ┌──────▼─────┐ ┌──────▼─────┐ ┌──────▼─────┐ ┌──────▼─────┐ ┌───▼────┐
 │  Chainway  │ │   Zebra    │ │   Seuic    │ │    ATID    │ │  Mock  │
 │ C72 / C66  │ │  MC3300R   │ │  AUTOID9U  │ │   AT911N   │ │ Driver │
 └────────────┘ └────────────┘ └────────────┘ └──────────┘ └────────┘
```

---

## 🛠️ Build & Setup Guide

### Requirements
* **JDK**: Version 17
* **Android Studio**: Iguana / Jellyfish (or Gradle 8.2+)
* **Android SDK**: Compile SDK 34 / Min SDK 26 (Android 8.0 Oreo+)

### Clone & Build
```bash
# Clone the repository
git clone https://github.com/Pushpendera5/HANDHELD-DEVICE-UHF-RFID-TAG-READ-AND-WRITE-IN-ATA-FORMATE-.git
cd HANDHELD-DEVICE-UHF-RFID-TAG-READ-AND-WRITE-IN-ATA-FORMATE-

# Build the Debug APK using Gradle wrapper
./gradlew assembleDebug
```

The compiled APK will be located at:
```text
app/build/outputs/apk/debug/app-debug.apk
```

### Running on Emulators (Mock Driver)
No physical RFID handheld terminal? No problem!
1. Launch the app on any Android emulator or standard smartphone.
2. The application will automatically fall back to the **Mock Software Reader** (or choose "Mock Reader" from the device dropdown in UHF Config).
3. Generate realistic simulated ATA tags and test the complete UI and business logic workflow.

---

## 📄 License & Attribution

This project is licensed under the [Apache License, Version 2.0](LICENSE).  
Portions of the underlying HAL and middleware base are derived from OpenRFID under the Apache 2.0 License.

You are free to use, modify, and distribute this software in accordance with the terms of the Apache 2.0 License.
