# Root Panic

Root utility app with four tools:

- **Kernel Panic** — checks for root permission, then runs the Linux SysRq crash command.
- **Kill SystemUI** — checks for root permission, reads the SystemUI package UID, saves it to a temporary text file, then stops only processes whose UID and process name both match SystemUI.
- **Reboot** — checks for root permission, asks for confirmation, then runs a normal full-device reboot with `reboot`. This restarts Android userspace and the Linux kernel.
- **System Log** — starts a root logcat capture after `BOOT_COMPLETED`, filters to warnings and errors from the main, system, and crash buffers, and displays a small recent tail. Refresh also starts capture if it is not already running.

## System Log limits

The boot receiver starts root logcat capture after Android sends `BOOT_COMPLETED`, when user storage is available. The capture includes warning/error messages only (`*:W`) from the main, system, and crash buffers. Logcat rotates at 128 KB with two rotated files (approximately 384 KB maximum). The viewer reads at most the latest 160 lines and 24 KB, reducing memory use on low-RAM devices.

Automatic capture requires the app to have been launched once and root permission to have been granted to it. If root was not previously authorized, open System Log and press Refresh after granting root. Capture starts after boot completion, not during the earliest kernel/init stages.

## Temporary UID file

The UID is stored at the app's private files directory as `systemui_uid.tmp`. It can be deleted from the Kill SystemUI screen and is also deleted automatically after the next boot.

## Safety

Kernel Panic intentionally crashes the kernel and forces a reboot. Unsaved work can be lost and filesystem corruption is possible if storage writes are in progress.

Kill SystemUI can make the status bar, navigation UI, and recent-app interface disappear briefly. Android may restart SystemUI automatically. The app deliberately matches the process name as well as the UID so it does not kill unrelated Android processes that may share the same UID.

Reboot performs a normal full-device restart. It is not a factory reset and does not erase user data.

## Build

Open this directory in Android Studio with JDK 17 and Android SDK 35 installed, then build the debug APK.
