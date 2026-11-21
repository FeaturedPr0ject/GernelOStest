# Root Panic

Root utility app with four tools:

- **Kernel Panic** — checks for root permission, then runs the Linux SysRq crash command.
- **Kill SystemUI** — checks for root permission, reads the SystemUI package UID, saves it to a temporary text file, then stops only processes whose UID and process name both match SystemUI.
- **Reboot** — checks for root permission, asks for confirmation, then runs a normal full-device reboot with `reboot`. This restarts Android userspace and the Linux kernel.
- **System Log** — checks for root permission and displays up to the latest 1,500 retained lines from all Android logcat buffers. Use Refresh System Log to load the latest available entries.

## System Log limitations

Android logcat uses limited-size ring buffers. Older messages can be overwritten as the device continues running. The viewer can show entries still retained by the system, but cannot guarantee that every event since boot is still available or recover overwritten messages.

## Temporary UID file

The UID is stored at the app's private files directory as `systemui_uid.tmp`. It can be deleted from the Kill SystemUI screen and is also deleted automatically after the next boot.

## Safety

Kernel Panic intentionally crashes the kernel and forces a reboot. Unsaved work can be lost and filesystem corruption is possible if storage writes are in progress.

Kill SystemUI can make the status bar, navigation UI, and recent-app interface disappear briefly. Android may restart SystemUI automatically. The app deliberately matches the process name as well as the UID so it does not kill unrelated Android processes that may share the same UID.

Reboot performs a normal full-device restart. It is not a factory reset and does not erase user data.

## Build

Open this directory in Android Studio with JDK 17 and Android SDK 35 installed, then build the debug APK.
