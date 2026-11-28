# PersonalMedia independent Android release — M05e

This page prepares an **independently installable signed APK** for GitHub Releases and other stores. It does not change the application ID or replace the Google Play App Bundle workflow. The repository deliberately contains **no signing secrets or third-party native binaries**.

## What is ready

- Package: `com.personalmedia.android`
- Version: `200012` / `20.0.12-android-play-m05e`
- Minimum Android: API 26; CPU: **ARM64 only** (`arm64-v8a`)
- Native libtorrent verified-piece streaming and native MPV are **required**; a build without them is rejected.
- User-reported Honor 90 tests: local library/playback, Stremio video, subtitles/fullscreen, August T405, backup/restore. The test is not a certification of every device or stream.

## Build an independent signed APK on the Windows ARM64 development VM

Run in **Windows PowerShell** with the complete working M05e Android Studio project and installed STR5C native dependencies. Do not run against a fresh clone until the native MPV and libtorrent dependencies have been restored per [BUILDING.md](BUILDING.md).

```powershell
cd "$env:USERPROFILE\Downloads\PersonalMedia"
$env:JAVA_HOME = "C:\Path\To\Your\JDK17"  # replace with your installed JDK 17 path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned

# Only if Windows marked THIS reviewed, downloaded script as blocked:
Unblock-File ".\tools\M05e-Build-Signed-Independent-Apk.ps1"

.\tools\M05e-Build-Signed-Independent-Apk.ps1 `
    -KeyStorePath "$env:USERPROFILE\PersonalMedia-Play-Keys\personalmedia-upload.p12" `
    -KeyAlias personalmedia-upload
```

The script performs a clean ARM64 release build with real torrent support, tests, checks the APK's native libraries for 16 KB ELF compatibility, runs Android `zipalign` and `apksigner`, verifies the signature, validates the manifest package/version and prints a **SHA-256 hash**. It prompts for the private keystore password on the VM; no password or key is included in GitHub.

Expected output (with a unique timestamp):

```text
dist\github-m05e\PersonalMedia-M05e-arm64-v200012-YYYYMMDD-HHMMSS.apk
```

The existing `tools/M05-Build-Signed-Play-Aab.ps1` creates an `.aab` for Play Console. **Do not upload that AAB as a GitHub phone-install APK.**

## Verify before a public release

1. Confirm the build printed `SUCCESS`, native-library checks all passed, and `apksigner verify` passed. Record the **exact** APK SHA-256 checksum.
2. Install and test this **exact signed APK** on a compatible ARM64 Android device (or a spare test device), including local media, Stremio streams, audio/CC, fullscreen, live TV, and backup/restore. Confirm it does not crash on launch.
3. Review redistribution rights and copyright notices for the actual bundled MPV/FFmpeg, libtorrent, OpenSSL and other binary dependencies; ensure all licence obligations are met. Do not redistribute streams or media content in the APK.
4. Keep two secure backups of the signing keystore and credentials **outside GitHub**. Publish only the APK and checksum, never the keystore, signing passwords, app data, logs with tokens, or build-machine paths.
5. Check signing certificates before updating any installed version. **A Play Store installation may be signed with a different Google-managed app-signing certificate**, even when both packages share `com.personalmedia.android`. An incompatible APK cannot update it in place. Back up app data before considering uninstalling; uninstalling may erase it.

## Create the GitHub download

After synchronizing the reviewed M05e source to `main`:

1. Open [GitHub Releases for this repository](https://github.com/beyond-grid-live-edge/PersonalMedia-Android-Native/releases) and choose **Draft a new release**.
2. Use the tag `v20.0.12-android-m05e` targeting the reviewed M05e commit; name it **PersonalMedia Android M05e — ARM64**.
3. Attach the **tested signed `.apk`**, not the `.aab`, to the release. Include its full SHA-256 and supported-device/installation notes.
4. Prefer **Save draft** until licence review and the final install/upgrade smoke test have passed. Then **Publish release**.

The GitHub `Source code (zip)` auto-generated link is **not an APK**. An APK exists for users only after it is explicitly attached as a release asset.

Suggested notes:

> **M05e (Android ARM64, API 26+)** — phone-optimised PersonalMedia with local media library, native MPV playback, verified-piece torrent streaming through optional Stremio add-ons, subtitles, August T405 live TV, and local backup/restore. M05e includes automatic decoder selection and startup/peer progress messages. Stremio login is optional and handled by Stremio. Requires permission to access the media and streams you choose. Install from the attached `.apk`; this release is not an official Stremio APK. Torrent peers may see your IP address. Test on your device before relying on it. SHA-256: **insert the hash from the build**.

## Third-party stores

Different stores can require additional information or review. The GitHub APK is for direct distribution; do not assume it is automatically accepted or delivered as an update by Google Play, F-Droid or another store.
