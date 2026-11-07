# Sobosuba Mod Loader

An Android 7.0+ Compose app for installing Sobosuba mods without root by using
[Shizuku](https://github.com/RikkaApps/Shizuku).

## MVP flow

1. Start Shizuku with wireless debugging and grant this app Shizuku permission.
2. The Home screen detects either `com.keepeetron.sobosuba` or
   `com.keepeetron.sobosuba.beta`.
3. Patch the installed base APK. The app changes an existing
   `android:debuggable` boolean, signs the result with a device-keystore key,
   and installs it with `pm install -r`.
4. Choose a mod ZIP from the Mods screen. It must contain `mod-package.json`;
   files are copied to the game's `files/` directory through `run-as`.
5. Launch Sobosuba from Home.

The loader never distributes game files. It reads and patches the APK already
installed on the user's device. The binary AXML patcher handles both existing
`debuggable` attributes and release manifests where the attribute is absent.

## Build

The application ID is `xyz.elouan.sobosubamodloader`. Open the project in
Android Studio and run the `app` configuration. The project
uses Kotlin, Jetpack Compose Material 3, Shizuku API 12.2.0, and Android SDK
35.

## Automated APK builds

Every push builds a debug APK through GitHub Actions. Open the repository's
**Actions** tab, select **Build APK**, and open the latest successful run. The
installable file is under **Artifacts** as `sobosuba-mod-loader-debug`; download
and unzip it, then install `app-debug.apk` on an Android device. Debug APKs are
retained for 30 days.
