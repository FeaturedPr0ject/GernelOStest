# Root Panic v1.3

- Checks the latest GitHub Release automatically when the app opens.
- Shows an update banner with release notes and an explicit Update now / Later choice.
- Downloads the release APK only after approval.
- Verifies APK package name, version code, and signing certificate before installation.
- Tries Root first, then Shizuku when its permission is granted.
- Replaces the full-screen navigation with a compact left drawer.
- Application ID: `com.coneui.rootpanic`.

**Important:** Android only permits in-place updates when package ID and signing certificate match. The 1.2 APK built before stable signing was configured may need one replacement install before this update path can be used. Versions before the package rename use a different application ID and cannot update into this package.
