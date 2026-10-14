# VoAnyApp and VoAnyLib

## Layout

- `VoAnyApp`: application ID `li.power.app.voany`, Android Telecom integration,
  Shizuku permission flow, DialPad / Messages / Contacts / Settings and call UI.
- `VoAnyLib`: SIM authentication, network policy, foreground service, strongSwan
  bridge, lwIP adapter, IMS registration, SIP dialogs, ESP and RTP/audio codecs.
- `VoAnyLib/.upstream`: disposable GitHub source checkouts, pinned by
  `VoAnyLib/upstream.lock.json`; not application-owned source.
- `VoAnyLib/src/main/jni/libandroidbridge` and `VoAnyLib/native/lwip-android`:
  application-owned bridges, kept outside upstream repositories.
- `VoAnyLib/dev-artifacts`: preserved old build outputs, helper scripts,
  diagnostics and the verified pre-refactor backup. Nothing was deleted.

The old diagnostic UI source is retained inside the library for reference. The
new launcher and tunnel notification open VoAnyApp.

## Build

Requires JDK 17+, Android SDK 36, NDK 27.1.12297006, Git and Python 3.9+.
The first Gradle build resolves Android/Maven dependencies over the network;
subsequent builds can use `--offline` once the required artifacts are cached.

```powershell
.\gradlew.bat :VoAnyApp:assembleDebug :VoAnyLib:assembleDebug :VoAnyLib:testDebugUnitTest
```

Gradle runs `VoAnyLib/prepare-upstreams.py` before native configuration. It clones
missing dependencies at the pinned commits and verifies existing checkouts. It
does not reset a checkout with a different revision. Override the Python command
with `-PvoanyPython=python3` if necessary.

- lwIP is upstream STABLE-2_2_1_RELEASE without source modifications.
- strongSwan has one necessary core patch,
  `VoAnyLib/patches/strongswan-epdg-apn.patch`, for the ePDG APN IDr. All Android
  and lwIP bridge changes live separately. The patch is applied idempotently.
- The existing OpenSSL 3.5.4 arm64 static library and headers were preserved in
  `VoAnyLib/src/main/jni/openssl`. They are build inputs, excluded from source
  control. For a clean checkout, provision these before the native build. On
  Linux/WSL, unpack OpenSSL 3.5.4, set `ANDROID_NDK_ROOT` to a Linux NDK and
  `OPENSSL_SRC` to its sources, prepare the upstreams, then run
  `bash VoAnyLib/native/build-openssl.sh`. This wrapper uses the unmodified
  upstream strongSwan OpenSSL builder. Windows Gradle builds use the resulting
  static archive; the wrapper is not a Windows-hosted NDK build script.

Outputs:

- `VoAnyApp/build/outputs/apk/debug/VoAnyApp-debug.apk`
- `VoAnyLib/build/outputs/aar/VoAnyLib-debug.aar`

## Phone setup

1. Start Shizuku and authorize VoAny on the entry screen.
2. Open Settings, Refresh SIMs and explicitly select the desired SIM (SIM2 for
   this test setup). The application never silently selects SIM1.
3. The self-managed VoAny account is registered automatically with Android Telecom.
   No manual account activation or default dialer selection is required.
4. Grant microphone and notification permissions, then Start service.
   The default backend is lwIP; Android VPN mode also requests VPN consent.

Call placement and incoming announcements use `TelecomManager`, a self-managed
`ConnectionService`, and `PhoneAccount`. Answer/reject/hangup operate on Android
`Connection` objects. VoAny presents the call UI and owns communication audio.
CallStyle notifications provide answer/decline/hangup actions. Full-screen
incoming calls use Android's full-screen intent permission; if Android does not
grant it, users can open the call notification or enable it in Advanced settings.
CallStyle uses VoAnyService's foreground notification ID 201. Registration polling
preserves the call notification until the call ends; it does not publish a separate
standalone CallStyle notification, which Android 16 rejects.
Emergency numbers are not placed through VoAny.

## Service and registration policy

`VoAnyService` owns tunnel and IMS lifetime independently of the Activity.
Registration is checked through the Shizuku shell process for the selected
SIM's **WWAN** service state; IWLAN registration is excluded. A telephony callback
provides changes, with a one-second poll as a fallback. The watcher is rebound
after a Shizuku service restart.

Only a READY SIM with verified `NOT_REGISTERED` WWAN state may connect. HOME,
ROAMING, inactive SIM, unavailable Shizuku or unknown state stop the service and
disconnect IMS/ePDG. Cancellation invalidates queued connection work and stops
retries. ePDG retries are owned by VoAnyService rather than a second strongSwan
timer. A fresh explicit Start service is required after a policy stop.

Settings displays Network / ePDG / SIP independently. Red indicates unavailable;
yellow indicates connecting; green requires verified network isolation, ePDG,
IMS registration and an enabled Telecom account. Advanced settings starts hidden
and stores ePDG overrides per subscription. SIM/backend changes are disabled
while running. System bars, display cutouts and keyboard insets are applied to
the root so Android navigation controls do not obscure the bottom tabs.

## Library consumers

Depend on `project(':VoAnyLib')` and use `org.voany.lib.VoAny` for SIM selection,
service control, state and calls. Extend `org.voany.lib.VoAnyApplication` (or use
its merged manifest application) to initialize the strongSwan certificate/profile
store. The app-specific Telecom implementation stays in VoAnyApp.

The AAR contains native libraries, but external Maven dependencies remain required;
a bare AAR file does not carry Gradle transitive dependency metadata. Source module
consumption is currently the supported integration.

## Limits

Messages sends and receives SMS over IMS using the current protected SIP transport;
see `SMS-STATUS.md` for setup, status semantics and verification limits.
Contacts reads the Android contacts provider. The media engine currently supports
AMR-NB, AMR-WB and G.711, not EVS. Existing SIP limitations, including outgoing
preconditions and call-session timers, remain documented in `CALL-STATUS.md` and
`INCOMING-CALL-STATUS.md`. A custom IMS media session does not create a dedicated
guaranteed QoS bearer. Android may terminate foreground services on user force-stop;
START_STICKY is not a promise of uninterrupted service.

## Preserved backup

IPv6 address selection, dual-stack lwIP interface installation, and MOBIKE
physical-path migration/rebuild behavior are described in IPV6-MOBIKE-STATUS.md.

`VoAnyLib/dev-artifacts/backups/VoAny-before-library-20261006-152647.zip`

43,706 files, 312,699,992 bytes. ZIP CRC verified; SHA-256:

`82fb35b2912a82440a26fbcda22d2322f2007caaeb4ad0cc5f66ab6eb15dde3e`

The snapshot includes early directory moves and the full pre-refactor diff.
