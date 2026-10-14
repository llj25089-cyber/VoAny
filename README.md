# VoAny

VoAny is an Android app for IMS calls and messaging. It uses Shizuku to perform AKA authentication with the selected SIM, establish an ePDG tunnel, and register with IMS.

## Features

- Outgoing and incoming calls with Android Telecom integration.
- SMS over IMS, with messaging and contacts screens.
- SIM selection, connection status, and a background connection service.
- Selectable lwIP userspace or Android VPN backend, IPv4 / IPv6 support, and MOBIKE network migration.
- Advanced settings for ePDG, SMSC, and connection options.

Availability depends on the SIM and core network. Emergency calling is not supported.

## Project Structure

- `VoAnyApp`: application UI and Android Telecom integration.
- `VoAnyLib`: SIM authentication, ePDG, IMS, SIP, messaging, and audio components.
- [APP-ARCH.md](APP-ARCH.md): architecture and implementation details.

## Building

Requirements: JDK 17, Android SDK 36, NDK `27.1.12297006`, Git, and Python 3.9 or later. The current build supports `arm64-v8a` devices running Android 12 (API 31) or later.

Set the Android SDK path in `local.properties`, for example:

```properties
sdk.dir=C:/Users/your-name/AppData/Local/Android/Sdk
```

Gradle runs `VoAnyLib/prepare-upstreams.py` to fetch the pinned strongSwan and lwIP revisions from `upstream.lock.json` and apply the required project patch. The first build requires network access.

### Prepare OpenSSL

OpenSSL static libraries and headers are excluded from version control and must be prepared for a fresh checkout. On Linux / WSL, install a Linux Android NDK and extract the OpenSSL 3.5.4 sources, then run these commands from the project root:

```bash
python3 VoAnyLib/prepare-upstreams.py
export ANDROID_NDK_ROOT=/path/to/android-ndk
export OPENSSL_SRC=/path/to/openssl-3.5.4
bash VoAnyLib/native/build-openssl.sh
```

The output is placed in `VoAnyLib/src/main/jni/openssl`. Windows builds can use these artifacts, but the OpenSSL build script requires Linux.

### Build the App and Library

Windows PowerShell:

```powershell
.\gradlew.bat :VoAnyApp:assembleDebug :VoAnyLib:assembleDebug
.\gradlew.bat :VoAnyLib:testDebugUnitTest :VoAnyApp:lintDebug
```

On Linux / macOS, use `./gradlew`. Add `-PvoanyPython=python3` if your Python command is `python3`. Once dependencies are cached, you can add `--offline`.

Outputs:

- APK: `VoAnyApp/build/outputs/apk/debug/VoAnyApp-debug.apk`
- AAR: `VoAnyLib/build/outputs/aar/VoAnyLib-debug.aar`

## Usage

1. Install and start [Shizuku](https://shizuku.rikka.app/), then open VoAny and grant Shizuku permission.
2. In **Settings**, tap **Refresh SIMs**, select the SIM to use, and grant microphone and notification permissions.
3. Ensure the selected SIM is not registered on a cellular network. You can use **Phone Info** in [NetMonster](https://play.google.com/store/apps/details?id=cz.mroczis.netmonster): select the correct SIM and set its network mode to **GSM Only** or **WCDMA Only**, or turn off **Mobile Radio Power**. Available options vary by device.
4. GSM / WCDMA Only may still allow cellular registration. Confirm that VoAny shows **Network: Unregistered** and that the SIM remains accessible. Keep an internet connection available, such as Wi-Fi.
5. Tap **Start service**. The Android VPN backend requires VPN consent; the lwIP backend does not.
6. Wait for ePDG Connected, SIP Registered, and the green status indicator. Use **DialPad** to make calls, answer incoming calls, or use **Messages** for SMS.

VoAny connects only when the selected SIM is confirmed to be unregistered on WWAN. If it registers on a HOME or ROAMING network, the service stops immediately. After returning to an unregistered state, tap **Start service** again. Red indicates unavailable, yellow indicates connecting, and green indicates ready for calls.

**Advanced settings** is hidden by default and provides ePDG overrides, backend selection, IPv6 preferences, and a stop-service control. Stop the service before changing the SIM or backend. After testing, restore the original network mode and Mobile Radio Power setting in Phone Info.
