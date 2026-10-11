# Sethu — Offline Emergency Communication

> **Zero Internet. Zero Cellular. Resilient Bluetooth Mesh Store-and-Forward for Disaster Relief.**

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)]()
[![Tests](https://img.shields.io/badge/tests-188%20passed-success.svg)]()
[![Internet Permission](https://img.shields.io/badge/INTERNET%20permission-0%20(100%25%20offline)-blue.svg)]()
[![Target SDK](https://img.shields.io/badge/targetSdkVersion-35-blue.svg)]()
[![Min SDK](https://img.shields.io/badge/minSdkVersion-26-blue.svg)]()

---

## 📖 Overview

**Sethu** (Bridge) is an Android emergency communication application engineered for immediate post-disaster scenarios where cellular towers, power grids, and internet backbones are severed. 

Using **Bluetooth Classic RFCOMM**, Sethu creates an opportunistic store-and-forward relay network between survivors, couriers/volunteers, and disaster rescue coordinators. Emergency alerts move hop-by-hop from disconnected ground zero zones to emergency command posts with cryptographic durability and deterministic role-aware delivery guarantees.

---

## 🎯 Three-Phone Demonstration Architecture

The system operates across three distinct device roles:

```
        STAGE 1: Ground Zero Collection                  STAGE 2: Downstream Forwarding
   +--------------------+     +-------------------+     +-------------------+     +---------------------+
   |      iQOO 15       |     |     Vivo Y15      |     |     Vivo Y15      |     |  Motorola Edge 30   |
   |   [SOS SENDER]     |     |   [RELAY NODE]    |     |   [RELAY NODE]    |     |    [COORDINATOR]    |
   |                    |     |                   |     |                   |     |                     |
   | • Local Voice/AI   |     | • Durable Outbox  |     | • Auto-Forwarding |     | • Priority Triage   |
   | • GPS Capture      |     | • Crash-Resilient |     | • Session Pinning |     | • Real-time Stream  |
   | • One-Tap SOS      |     | • Zero Redial     |     | • Role Handshake  |     | • Offline Maps      |
   +---------+----------+     +---------+---------+     +---------+---------+     +----------+----------+
             |                          |                         |                          |
             +==== RFCOMM (Stage A/B) ==+                         +==== RFCOMM (Stage D/E) ==+
               Manual Connect: iQOO -> Vivo                         Manual Connect: Vivo -> Moto
               Automatic SOS Transmission                           Automatic Forward to Coordinator
```

### Roles Explained:
1. **iQOO 15 — SOS Sender (Survivor):** Creates emergency reports via one-tap presets, voice speech-to-text, or offline AI triage. Captures location coordinates and transmits to available relays.
2. **Vivo Y15 — Relay Node (Courier / First Responder):** Physically mobile bridge device. Receives and durably stores incoming reports, issues an intermediate receipt, holds them while travelling, and delivers them once reaching the coordinator.
3. **Motorola Edge 30 Pro — Emergency Coordinator (Command Center):** Disaster response command node. Ingests reports, runs automated priority triage (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`), renders live arrival banners, and plots incidents on offline maps.

---

## ⚡ The 5-Stage Connection & Delivery Workflow

Sethu adheres to a strict design principle: **Connections are manual; message forwarding is automatic.**

| Stage | Action | Description |
| :---: | :--- | :--- |
| **A** | **Start Server & Connect iQOO** | On Vivo, tap **Start Server** (`Listening`). On iQOO, open **Direct Device Link**, select Vivo, and tap **Connect**. Both phones show `Connected`. |
| **B** | **SOS Transfer & Durable ACK** | User sends an SOS on iQOO. Vivo validates the frame, durably writes it to disk, and sends back an ACK. Vivo marks the report `RECEIVED — PENDING COORDINATOR DELIVERY`. |
| **C** | **Deliberate Disconnection** | iQOO Bluetooth is turned OFF. Vivo detects socket closure, marks link `Standby`, retains the pending SOS on disk, and never triggers background redial loops. |
| **D** | **Connect Vivo to Coordinator** | On Motorola, tap **Start Server** (`Listening`). On Vivo, open **Direct Device Link**, select Motorola, and tap **Connect**. |
| **E** | **Automatic Forwarding** | Vivo's link-driven engine instantly detects the connected Coordinator peer and automatically transmits the pending report. Motorola validates, stores, and ACKs the report. Vivo marks it `DELIVERED_TO_COORDINATOR`. Motorola displays the incident at the top of its triage dashboard. |

---

## 🚀 Key Features & Capabilities

- **100% Strict Offline Operation:**
  - Guaranteed zero `android.permission.INTERNET`.
  - No cloud services, Firebase, remote API calls, or telemetry.
- **Link-Driven Store-and-Forward Engine:**
  - Background auto-dial loops completely removed to prevent battery drain and RF interference.
  - Automatic queue flushing as soon as a compatible peer is linked.
  - Atomic fsync-backed storage (`DurableJsonArrayFile`) protects against sudden battery loss or process death.
- **Two-Stage Acknowledgement Protocol:**
  - Sender $\to$ Relay: Stored durably before Relay ACK.
  - Relay $\to$ Coordinator: Stored durably before Coordinator ACK; Relay marks delivered only upon valid Coordinator ACK.
- **OEM Bluetooth Stack Resilience:**
  - Dual-mode fallback in `AndroidRfcommAdapter`: Automatically falls back to `listenUsingInsecureRfcommWithServiceRecord` and `createInsecureRfcommSocketToServiceRecord` when OEM MediaTek / legacy stacks reject secure SDP registration.
- **Offline AI & Voice Transcription:**
  - **Offline Speech-to-Text:** Integrated Vosk acoustic models for hands-free emergency reporting.
  - **Local AI Triage:** Local GGUF extraction using llama.cpp (`Qwen2.5-0.5B-Instruct`), parsing natural voice text into structured incident data without internet.
- **Offline Vector Maps:**
  - Offline tile and vector map rendering powered by Mapsforge.

---

## 🛠 Tech Stack

- **Platform:** Android (Min SDK 26, Target SDK 35)
- **Language:** Kotlin 2.0 (Coroutines, StateFlow, Serialization)
- **UI Framework:** Jetpack Compose & Material 3 (Dark disaster-optimised theme)
- **Bluetooth:** Android Bluetooth Classic RFCOMM (SPP UUID `00001101-0000-1000-8000-00805F9B34FB`)
- **Speech & Local AI:** Vosk Android SDK & llama.cpp JNI
- **Maps:** Mapsforge Core & Mapsforge Map

---

## 📁 Repository Structure

```
Sethu/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/sethu/emergency/
│   │   │   │   ├── ai/            # Offline AI extraction (llama.cpp)
│   │   │   │   ├── comm/          # RFCOMM transport, framing & store-and-forward
│   │   │   │   │   ├── bluetooth/ # RFCOMM transport & dual fallback adapter
│   │   │   │   │   ├── coordinator/# Triage classifier, report store & dashboard
│   │   │   │   │   ├── forwarding/# Link-driven store-and-forward engine
│   │   │   │   │   ├── model/     # Protocol frames, SosReport, SethuMessage
│   │   │   │   │   ├── relay/     # Relay report store & delivery states
│   │   │   │   │   └── sender/    # Sender report store & ACK tracker
│   │   │   │   ├── location/      # GPS coordinate extraction & utilities
│   │   │   │   ├── speech/        # Offline Vosk speech recognition
│   │   │   │   └── ui/            # Jetpack Compose screens & components
│   │   │   └── AndroidManifest.xml # Zero-internet permission manifest
│   │   └── test/                  # 188 unit & simulated end-to-end tests
│   └── build.gradle.kts
├── docs/                          # Release candidate reports and workflow specs
├── models/                        # Instructions for placing GGUF model weights
├── presentation/                  # Slide deck, voiceover scripts & media assets
├── build.gradle.kts
├── gradle.properties
└── settings.gradle.kts
```

---

## 🧪 Building & Verification

### Run Automated Test Suite
Sethu includes a 188-test JVM test suite with mock RFCOMM channels (`FakeRfcomm`) covering multi-hop handshakes, dropped connections, duplicate prevention, and store durability:

```powershell
.\gradlew.bat test --rerun-tasks
```

### Build Debug APK
```powershell
.\gradlew.bat assembleDebug
```
*Output:* `app/build/outputs/apk/debug/app-debug.apk` (~97 MB)

### Build Release APK
```powershell
.\gradlew.bat assembleRelease
```
*Output:* `app/build/outputs/apk/release/app-release-unsigned.apk` (~88 MB)

---

## 🔒 Security & Privacy Notice

- **No Internet Access:** Sethu does not declare or request `android.permission.INTERNET`. No network traffic leaves the device.
- **RFCOMM Encryption:** Data links utilize Android OS Bluetooth pairing encryption (E0 / AES-CCM).
- **Local Storage:** All emergency reports and incident coordinates reside strictly in private app storage (`/data/user/0/com.sethu.emergency/files/`). Cloud backups are explicitly disabled (`allowBackup="false"`).

---

## 👥 Contributors & Hackathon Team
Developed for offline emergency response and resilient mesh communications.
