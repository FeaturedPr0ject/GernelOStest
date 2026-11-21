# Root Panic

Root utility app with three tools:

- **Kernel Panic** — checks for root permission, then runs the Linux SysRq crash command.
- **Kill SystemUI** — checks for root permission, reads the SystemUI package UID, saves it to a temporary text file, then stops only processes whose UID and process name both match SystemUI.
- **Reboot** — offers **Soft Reboot** (restarts Android userspace while keeping the Linux kernel running) and **Reboot (Hard Reboot)** (restarts the whole device, including the kernel). Both actions check for root permission and ask for confirmation first.

## Temporary UID file

The UID is stored at the app's private files directory as `systemui_uid.tmp`. It can be deleted from the Kill SystemUI screen and is also deleted automatically after the next boot.

## Safety

Kernel Panic intentionally crashes the kernel and forces a reboot. Unsaved work can be lost and filesystem corruption is possible if storage writes are in progress.

Kill SystemUI can make the status bar, navigation UI, and recent-app interface disappear briefly. Android may restart SystemUI automatically. The app deliberately matches the process name as well as the UID so it does not kill unrelated Android processes that may share the same UID.

Soft Reboot support depends on the Android build. If the device does not support the userspace reboot command, the command may fail; the app does not guarantee that boot animation will appear. Hard Reboot restarts the device and is not a factory reset.

## Build

Open this directory in Android Studio with JDK 17 and Android SDK 35 installed, then build the debug APK.
