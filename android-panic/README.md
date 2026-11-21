# Apollo Panic

Minimal Android app for Alcatel 9032X / Apollo_8_4G (Android 10, ARM32).

## Behavior

- One action button: **Panic Now**.
- Requests root through Magisk `su`.
- Checks UID 0, the SysRq control and trigger nodes, and trigger write access.
- Runs the crash command only after all checks pass.

The correct Linux SysRq crash command is:

```sh
echo c > /proc/sysrq-trigger
```

It is **not** `echo -c`; that does not request the SysRq crash operation.

## Build

Open this directory in Android Studio with JDK 17 and Android SDK 35 installed, then build the debug APK.

## Warning

Panic Now intentionally crashes the kernel and forces a reboot. Unsaved work can be lost and filesystem corruption is possible if storage writes are in progress. The app does not change SysRq settings or flash partitions.
