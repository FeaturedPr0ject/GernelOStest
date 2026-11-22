# Root Panic

- **Application ID / namespace:** `com.coneui.rootpanic`
- **Current app version:** 1.2 (version code 3)

Root utility app for Android 10+ with a compact dark Compose interface.

## Screens

- **Welcome** — landing page with the author profile and shortcuts to the tools.
- **Kernel Panic** — checks Root, Android build details, kernel version, SysRq support, trigger writability, memory and SELinux state. A confirmation dialog appears before the intentional kernel crash.
- **Kill SystemUI** — checks Root, reads the SystemUI package UID, saves it temporarily, then stops only processes whose UID and process name both match SystemUI.
- **System Log** — starts a root logcat capture after `BOOT_COMPLETED`, filters warnings and errors from the main, system, and crash buffers, and refreshes the viewer automatically about every 400 ms. There is no manual refresh button.
- **Settings** — checks Root and identifies a known provider (Magisk, KernelSU, or APatch). Shizuku/Sui is checked independently using the official Shizuku API and permission request flow. The version footer supports a five-tap latest-release check.

## System Log limits

The boot receiver starts root logcat capture after Android sends `BOOT_COMPLETED`, when user storage is available. The capture includes warning/error messages only (`*:W`) from the main, system, and crash buffers. Logcat rotates at 128 KB with two rotated files (approximately 384 KB maximum). The viewer reads at most the latest 160 lines and 24 KB, reducing memory use on low-RAM devices.

The viewer polls the app-private log file every 400 ms. The root logcat process writes continuously in the background. Automatic capture requires the app to have been launched once and Root permission to have been granted to it. If Root was not previously authorized, open the app and approve Root access. Capture starts after boot completion, not during the earliest kernel/init stages.

## Shizuku / Sui

The app includes the official Shizuku API and provider. Start Shizuku or install Sui, then open Settings and request this app's Shizuku permission. The app shows whether the service is connected, whether permission is granted, and whether the service runs with Root UID 0 or ADB UID 2000. Shizuku permission is separate from superuser Root permission.

Shizuku documentation: https://github.com/RikkaApps/Shizuku-API

## Stable APK signing and in-place updates

Android only accepts an in-place update when the new APK is signed with the same signing certificate as the installed APK. A GitHub-hosted runner's automatically generated debug key is not stable between fresh runners. For dependable updates, create one private upload keystore and store it only in GitHub repository Actions secrets. Never commit the keystore or its passwords.

Create the keystore locally with JDK 17:

```powershell
keytool -genkeypair -v -keystore root-panic-upload.jks -alias rootpanic -keyalg RSA -keysize 3072 -validity 10000
$bytes = [System.IO.File]::ReadAllBytes(".\root-panic-upload.jks")
[Convert]::ToBase64String($bytes) | Set-Clipboard
```

In GitHub, open **Settings → Secrets and variables → Actions** and add these repository secrets:

- `ROOT_PANIC_KEYSTORE_BASE64` — the Base64 text copied from PowerShell.
- `ROOT_PANIC_KEYSTORE_PASSWORD` — the keystore password.
- `ROOT_PANIC_KEY_ALIAS` — `rootpanic` (or the alias chosen above).
- `ROOT_PANIC_KEY_PASSWORD` — the key password.

After these secrets are configured, the workflow signs both debug and release APKs with that same keystore. Install the stable-key APK once; future builds signed by the same key can update it without uninstalling. An APK already installed with a different debug certificate must be replaced once before this stable update path can be used.

When the secrets are absent, Actions still builds a debug APK so the source can be tested, but the runner-generated debug signature is not suitable for reliable in-place updates.

## Temporary UID file

The SystemUI UID is stored in the app's private files directory as `systemui_uid.tmp`. It can be deleted from the Kill SystemUI screen and is also deleted automatically after the next boot.

## Safety

Kernel Panic intentionally crashes the kernel and forces a reboot. Unsaved work can be lost and filesystem corruption is possible if storage writes are in progress.

Kill SystemUI can make the status bar, navigation UI, and recent-app interface disappear briefly. Android may restart SystemUI automatically. The app matches the process name as well as the UID so it does not kill unrelated Android processes that may share the same UID.

## Build

Open this directory in Android Studio with JDK 17 and Android SDK 35 installed, then build the debug APK. Shizuku API version 13.1.5 is used.
