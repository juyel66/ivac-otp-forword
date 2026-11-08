# IVAC Assistant Mobile (Android Companion App)

Native Android companion application for the **IVAC Assistant Chrome Extension** and **Node.js backend**, built with Kotlin, Jetpack Compose, Material 3, and modern Android architecture.

---

## 1. Architectural Overview & Workflow

The mobile app acts as an automated, secure SMS OTP relay bridge. When the user books an Indian visa appointment on `appointment.ivacbd.com`, IVAC sends a 6-digit authentication OTP via SMS. The companion app intercepts this SMS, parses the OTP, and immediately forwards it to the paired Chrome Extension via the Node.js backend relay.

```
┌─────────────────────────────────┐               ┌────────────────────────────────┐
│   IVAC Visa Portal (SMS Gateway) │               │   IVAC Assistant Chrome Ext    │
└────────────────┬────────────────┘               └───────────────▲────────────────┘
                 │ SMS OTP                                        │ Polls /v1/otp/poll
                 ▼                                                │ every 2 seconds
┌─────────────────────────────────┐     HTTPS POST /v1/otp/push   │
│  IVAC Assistant Mobile (App)    ├───────────────────────────────┴────────────────┐
│  - BroadcastReceiver (SMS)      │                                                │
│  - OtpParser (Numeric + Words)  │          ┌──────────────────────────────────┐  │
│  - Encrypted Keystore Storage   │          │  Backend Relay (Node.js/Worker)  │  │
│  - Deduplication Cache (120s)   ├─────────►│  POST /v1/otp/push               │  │
│  - Zero OTP Disk/Log Policy     │          │  POST /v1/otp/poll               │  │
└─────────────────────────────────┘          └──────────────────────────────────┘  │
```

---

## 2. Core Features & Screens

1. **Screen 1: Welcome**
   - Official IVAC Assistant branding.
   - Clear value proposition: high-speed automated OTP forwarding.
   - Privacy guarantees & Continue navigation.

2. **Screen 2: Connect Extension**
   - Extension Client Key input (format: `6988-P97K`).
   - Mobile phone number input (required for relay routing).
   - Real-time syntax validation.
   - Backend verification before marking the device as paired.

3. **Screen 3: SMS Permission**
   - Clear disclosure of why SMS permission is required.
   - Requests only necessary permissions: `RECEIVE_SMS` and `READ_SMS` (and `POST_NOTIFICATIONS` on Android 13+).
   - Explicit confirmation of data privacy.

4. **Screen 4: Connected Dashboard**
   - Live Connection Status (Paired & Ready / Disconnected).
   - Paired Extension Key (masked: `6988-****`).
   - SMS Forwarding ON/OFF toggle switch.
   - Last message forwarding status and timestamp.
   - Real-time connection tester (`Test Relay`).
   - Disconnect and re-pair options.
   - Battery optimization warning banner for manufacturer background policies.

5. **Screen 5: Settings & Rules**
   - Configurable Backend Base URL (`https://api-ivacassistant.codiumzenit.com`).
   - Configurable Sender Matching Patterns (`IVAC`, `IVACBD`, `BTCL`, `INFOSMS`, etc.).
   - Configurable Keyword Matching Rules (`IVAC`, `VISA`, `APPOINTMENT`, `OTP`, etc.).
   - Manufacturer-specific battery guides (MIUI, Samsung OneUI, ColorOS, EMUI).

---

## 3. Supported SMS Formats & Parsing Rules

The built-in `OtpParser` automatically handles diverse IVAC SMS formats:

1. **Standard 6-digit numeric OTP:**
   > *"Your IVAC Bangladesh appointment login verification code is 420318. Valid for 5 minutes."*  
   > Extracted OTP: **`420318`**

2. **Hyphenated word-form numbers:**
   > *"Dear Applicant, your IVAC portal verification code is Four-Two-Zero-Three-One-Eight. Do not share with anyone."*  
   > Extracted OTP: **`420318`**

3. **Space-separated word numbers:**
   > *"IVAC: code is four two zero three one eight"*  
   > Extracted OTP: **`420318`**

4. **Bengali numeric digits:**
   > *"IVAC ভেরিফিকেশন কোড ৪২০৩১৮"*  
   > Extracted OTP: **`420318`**

### Privacy & Security Guarantees:
- **Zero Disk Persistence:** OTP digits are never saved to DataStore, SQLite, or SharedPreferences.
- **Zero Logcat Exposure:** Network interceptors and application logs sanitize and redact OTP digits.
- **De-duplication Protection:** In-memory cache discards duplicate deliveries within a 120-second window.
- **Unrelated SMS Discarded:** SMS from non-IVAC senders (banks, friends, marketing) are discarded immediately without inspection.

---

## 4. Windows Command-Line Build Instructions

The project is configured to build completely from Windows PowerShell or CMD without requiring Android Studio.

### Prerequisites
- **JDK 17 or newer**: Ensure `JAVA_HOME` is set.
- **Android SDK (API 34)**: Ensure `ANDROID_HOME` is set.

### 1-Click Build Command
From the project root directory (`Mobile App`):

```powershell
.\gradlew.bat assembleDebug
```

### Expected Output
Upon successful compilation, the debug APK is located at:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## 5. Release Signing Instructions

To generate a production-ready signed APK:

1. **Generate a keystore (if you do not have one):**
   ```cmd
   keytool -genkey -v -keystore ivac-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias ivac-key
   ```

2. **Set environment variables:**
   ```powershell
   $env:KEYSTORE_PATH="C:\path\to\ivac-release-key.jks"
   $env:KEYSTORE_PASSWORD="your-keystore-password"
   $env:KEY_ALIAS="ivac-key"
   $env:KEY_PASSWORD="your-key-password"
   ```

3. **Build the release APK:**
   ```powershell
   .\gradlew.bat assembleRelease
   ```

The signed release APK will be generated at:
```
app/build/outputs/apk/release/app-release.apk
```

---

## 6. GitHub Actions Cloud CI/CD

A complete cloud build workflow is included in `.github/workflows/build.yml`.

Every push or pull request to `main` or `master` triggers:
1. Environment setup with JDK 17 and Android SDK 34.
2. Execution of unit tests (`OtpParserTest`).
3. Automated compilation of `app-debug.apk`.
4. Artifact upload available for direct download in the GitHub Actions tab.

---

## 7. Real-Device Testing Checklist

| Step | Action | Expected Result |
|---|---|---|
| **1. Install APK** | Run `adb install app-debug.apk` | App installs and launches with Welcome screen. |
| **2. Pairing** | Enter Extension Client Key (e.g. `6988-P97K`) and phone | Backend verifies key and advances to Permissions. |
| **3. Permission** | Click "Grant SMS Permission" | Android permission dialog displays; user grants access. |
| **4. Dashboard** | Check status indicator | Displays "Connected & Paired", masked key `6988-****`. |
| **5. Test Relay** | Click "Test Relay" button | Verifies HTTP response from backend relay. |
| **6. Real SMS Test** | Trigger IVAC login SMS on `appointment.ivacbd.com` | App intercepts SMS, extracts OTP, and pushes to relay. |
| **7. Chrome Extension** | Observe Chrome Extension popup | Chrome Extension auto-fills OTP and logs in. |
| **8. Battery Policy** | Check Manufacturer Guide in Settings | Verify autostart / battery saver exceptions for MIUI/Samsung. |
| **9. Device Reboot** | Reboot Android device | App verifies pairing state on boot and remains armed. |

---

## 8. Backend Integration & API Contract Audit

### Existing Backend Endpoints (Inspected)
1. `POST /v1/otp/push`
   - Request: `{ "phone": "017...", "pairCode": "6988-P97K", "otp": "420318" }`
   - Response: `{ "ok": true, "message": "OTP pushed successfully", "id": "..." }`
2. `POST /v1/otp/poll`
   - Request: `{ "phone": "017...", "pairCode": "6988-P97K", "since": 1731050000000 }`
   - Response: `{ "ok": true, "otp": "420318" }`

### Recommended Backend Upgrades (Proposed Contract)
- **`POST /v1/companion/pair`**: Verify companion phone and bind to extension install ID with an ephemeral device token.
- **`GET /v1/health`**: Lightweight health check endpoint for companion status pings.
- **HMAC Signature**: Include `X-Companion-Signature` header to authenticate OTP push payloads cryptographically.

