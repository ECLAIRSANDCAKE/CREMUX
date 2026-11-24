# Building Crémux

## With GitHub Actions (no computer needed)
1. Push to `main` (the **GitHub Sync** screen in the app can do this).
2. Open the **Actions** tab, then the latest run.
3. Download the artifact at the bottom:
   - `Cremux-arm64-v8a` for most phones
   - `Cremux-armeabi-v7a` for older 32-bit phones

## Publishing a release
1. Releases → **Create a new release**.
2. Create a tag such as `v0.1.0`, add a title and notes, tick **pre-release** (these are debug builds), then publish.
3. The build starts and attaches both APKs to that release a few minutes later.

You can also tag from a terminal: `git tag v0.1.0 && git push origin v0.1.0`.

## Locally
Needs JDK 11 and the Android SDK:
```
chmod +x gradlew
PACKAGE_VARIANT=apt-android-7 ./gradlew assembleDebug
```
APKs are written to `app/build/outputs/apk/debug/`.

## Signing CI builds with your own key

Add these repository secrets (Settings → Secrets and variables → Actions): `CREMUX_KEYSTORE_BASE64` (output of `base64 -w0 your.jks`), `CREMUX_KEYSTORE_PASSWORD`, `CREMUX_KEY_ALIAS`, and optionally `CREMUX_KEY_PASSWORD`. Without them the APK is signed with a throwaway debug key and cannot be updated in place by a later build. Never commit the keystore.
