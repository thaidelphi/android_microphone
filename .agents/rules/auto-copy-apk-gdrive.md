# APK Auto-Copy to Google Drive Rule

Whenever APKs are built (Debug or Release) in this project:
1. `app/build.gradle.kts` has an automatic Gradle hook that copies `D:\myproject1\Android_MIC\app\build\outputs\apk` to `G:\My Drive\APKs\aMyAPP\apk` automatically.
2. The AI assistant must always ensure that the latest APKs are copied to:
   `G:\My Drive\APKs\aMyAPP\apk`
   (specifically `app-release.apk` and `app-debug.apk` in their respective folders).
3. Always verify that the destination directory contains the newly generated APK files.
