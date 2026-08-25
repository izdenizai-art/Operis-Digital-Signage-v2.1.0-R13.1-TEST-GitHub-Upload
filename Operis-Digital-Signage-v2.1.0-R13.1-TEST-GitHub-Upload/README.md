# Operis Digital Signage v2.1.0-R13.1 TEST

Android TV / TV Box player source project.

## Build identity

- Package: `tr.izdeniz.signage`
- VersionCode: `20103`
- VersionName: `2.1.0-r13.1-test`
- minSdk: `30`
- targetSdk / compileSdk: `35`
- Visible product name: `Operis Digital Signage`

R13.1 starts from the R12 installation-proven manifest surface. The boot receiver listens only for `BOOT_COMPLETED`; R13-only quick-boot/unlock/update broadcasts and `singleTask` are intentionally not carried into this recovery build.

## Player capabilities in this source

- Passenger information, advertising/information, mixed-layout and full-screen playlist modes.
- Server-driven dynamic layout and typography.
- Scheduled image/video playlists, priorities, daily/absolute windows and return-to-base behavior.
- Persisted publication, schedule and trusted-time state for offline continuation and offline reopen/reboot recovery.
- App-private verified media cache; remote media is downloaded through the native same-origin API path.
- Protected 8-row service menu including `Uygulamayı Kapat`.
- Server command handling for reconnect, publication/profile/time/telemetry/playlist/schedule refresh, return to base, preview capture, app restart and privileged device commands.
- Device Owner gated reboot and kiosk controls. Unsupported privileged operations are reported as unsupported rather than faked.
- RAM/storage/network/model/version telemetry plus best-effort CPU/GPU/temperature reads. Unavailable values are explicitly `Desteklenmiyor`.
- Player-surface JPEG preview capture/upload. This is not VNC or full Android remote desktop.
- Conditional WOL metadata discovery only. A powered-off Android APK cannot power the device on by itself; a real WOL/vendor/MDM/relay integration is still required.

## Security boundaries

- `setAllowUniversalAccessFromFileURLs(true)` is not enabled.
- Device token is stored in native Android preferences, not HTML/JavaScript.
- Service PIN is native-side only; it is not embedded in HTML/JavaScript.
- Network API operations are performed by the native Android layer.
- Media download paths are restricted to same-origin `/media/...` URLs and verified when a SHA-256 is supplied.
- No private signing key or keystore is included in this repository or output ZIP.

## Build

GitHub Actions workflow: `.github/workflows/build-r13-1-test.yml`.

The workflow runs source contracts, standalone Java policy tests, Android unit tests, `assembleDebug`, `zipalign`, `apksigner`, and `aapt` package/version/label verification before publishing the artifact.

Optional fixed TEST signing is supported through GitHub Actions Secrets:

- `OPERIS_TEST_KEYSTORE_B64`
- `OPERIS_TEST_KEYSTORE_PASSWORD`
- `OPERIS_TEST_KEY_ALIAS`
- `OPERIS_TEST_KEY_PASSWORD`

If these secrets are absent, GitHub produces a clean-install debug-signed TEST APK. Never place a private keystore in source control or in the source ZIP.

See `GITHUB-BUILD-R13.1.txt` for upload/build steps.

## Verification status

Source-contract, JavaScript and pure-Java policy tests can run without the Android SDK. Android Gradle/JUnit/APK compilation is intentionally verified by GitHub Actions in the supplied workflow. The APK is not considered field-approved until the resulting artifact is clean-installed and exercised on the same target TV Box used for R12 testing.

Server-side endpoints/UI for device assignment, campaign administration and preview display are separate server work; APK-side contracts in this source do not by themselves make those server features complete.
