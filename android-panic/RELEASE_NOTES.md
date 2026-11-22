# Root Panic v1.3

- Checks the latest GitHub Release automatically when the app opens.
- Shows an update banner with release notes and an explicit Update now / Later choice.
- Downloads the release APK only after approval.
- Verifies APK package name, version code, and signing certificate before installation.
- Tries Root first, then Shizuku when its permission is granted.
- Replaces the full-screen navigation with a compact left drawer.
- Application ID: `com.coneui.rootpanic`.

**Important:** Android only permits in-place updates when package ID and signing certificate match. The 1.2 APK built before stable signing was configured may need one replacement install before this update path can be used. Versions before the package rename use a different application ID and cannot update into this package.


## Archived earlier builds

The v1.0, v1.1, and v1.2 Releases contain the original Debug APK artifacts for historical download. They use temporary runner signatures and are not guaranteed to install as updates over one another. Versions 1.0–1.1 also use the former application ID `com.gernelos.apollopanic`; version 1.2 uses `com.coneui.rootpanic`. The v1.3 stable-signed APK is the supported update track after its first clean installation.
