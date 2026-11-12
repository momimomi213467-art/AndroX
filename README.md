# AndroX

An Android calculator-style launcher for a PIN-protected app list.

## Current features
- Calculator-style entry screen.
- Create a 4–8 digit PIN on first launch.
- Add or remove launchable apps from the private list.
- Launch selected apps from the vault.
- Change the PIN and lock the vault.

## Build
Open this repository in Android Studio and let Gradle sync. Use JDK 17 and Android SDK 35, then build the **debug APK** from **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

The project uses the Android SDK only; no third-party runtime libraries are required.

## Important Android limitation
This app does **not** clone other APKs and cannot universally hide installed apps from the system launcher or Settings. Android restricts those operations for ordinary apps. The vault stores a private list and launches the original installed apps. For stronger isolation or system-level hiding, use Android's supported Private Space feature where available.

## Security note
This is an initial prototype. The PIN is stored in app-private preferences but is not yet protected by Android Keystore or biometric authentication. Do not use it to protect highly sensitive information.
