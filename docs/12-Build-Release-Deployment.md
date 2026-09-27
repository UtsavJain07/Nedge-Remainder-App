# 12 — Build, Release & Deployment

## 1. Repository layout

```
nudge/
├── .github/workflows/ci.yml
├── .github/workflows/release.yml
├── app/  core/  feature/  build-logic/  benchmark/
├── docs/                      ← copy of these Documents
├── gradle/libs.versions.toml
├── keystore/                  ← gitignored
├── CHANGELOG.md
├── README.md
└── local.properties           ← gitignored
```

`.gitignore`: `local.properties`, `*.jks`, `*.keystore`, `keystore/`, `/build`, `**/build`, `.idea/` (except codeStyles), `*.iml`, `.gradle/`, `captures/`.

## 2. Versioning

- SemVer `MAJOR.MINOR.PATCH` in `gradle.properties` (`nudge.versionName=1.0.0`).
- `versionCode = MAJOR*10000 + MINOR*100 + PATCH` (1.0.0 → 10000). CI can add a build offset.
- Git tag `v1.0.0` triggers the release workflow.
- `CHANGELOG.md` in Keep a Changelog format.

## 3. CI (`.github/workflows/ci.yml`)

Trigger: push to any branch and pull requests to `main`.

```yaml
name: CI
on: { push: { branches: ['**'] }, pull_request: { branches: [main] } }
concurrency: { group: ci-${{ github.ref }}, cancel-in-progress: true }
jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug --stacktrace
      - uses: actions/upload-artifact@v4
        if: always()
        with: { name: reports, path: '**/build/reports/**' }
      - uses: actions/upload-artifact@v4
        with: { name: app-debug, path: app/build/outputs/apk/debug/*.apk }
  instrumented:
    runs-on: ubuntu-latest
    needs: build
    if: github.event_name == 'pull_request'
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - uses: gradle/actions/setup-gradle@v4
      - name: Enable KVM
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules && sudo udevadm trigger --name-match=kvm
      - uses: reactivecircus/android-emulator-runner@v2
        with: { api-level: 34, arch: x86_64, target: google_apis, script: ./gradlew connectedDebugAndroidTest }
```

Branch protection on `main`: CI required, 1 review (or self-review for a solo developer), linear history.

## 4. Signing

- Generate the upload keystore once:
  `keytool -genkeypair -v -keystore keystore/nudge-upload.jks -alias nudge -keyalg RSA -keysize 4096 -validity 10000`
- Store it in GitHub Secrets: `KEYSTORE_BASE64` (base64 of the .jks), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
- `app/build.gradle.kts` reads the signing config from environment variables when present, else from `local.properties`. The keystore is **never** committed. Back it up in a password manager.
- On Google Play: enroll in **Play App Signing** (Google holds the app signing key; we hold the upload key).

## 5. Release build config

```kotlin
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        signingConfig = signingConfigs.getByName("release")
    }
}
```

`proguard-rules.pro`: keep `@Serializable` classes (kotlinx-serialization rules), keep route classes, and Room/Hilt consumer rules (bundled). Verify with a release-build smoke test in CI (`assembleRelease` + install on the emulator + launch).

## 6. Release workflow (`.github/workflows/release.yml`)

Trigger: a tag push `v*`.

Steps:
1. Checkout, JDK 17, Gradle.
2. Decode the keystore from secrets into `keystore/nudge-upload.jks`.
3. `./gradlew testReleaseUnitTest bundleRelease assembleRelease`.
4. Create a GitHub Release with the `.apk` (sideload) and `.aab` attached, and the notes taken from the `CHANGELOG.md` section.
5. (Optional, when on Play) Upload the AAB to the **internal testing** track with `r0adkll/upload-google-play@v1` using `PLAY_SERVICE_ACCOUNT_JSON`. Promote to production manually in Play Console.

## 7. Distribution options

| Option | When | Steps |
|--------|------|-------|
| **Sideload APK** (personal use) | Immediately | Download the APK from the GitHub Release → install (allow "Install unknown apps"). Updates install over it (same signing key) |
| **Firebase App Distribution** | Testing with friends | Upload the APK in the release workflow; testers get email invites (needs Firebase; no SDK in the app is required) |
| **Google Play** | Public | Developer account ($25 one-time) → create the app → store listing → **Data safety form** (v1: "No data collected", "No data shared") → content rating → target audience → internal testing → closed testing (Play requires 12+ testers for 14 days for new personal accounts) → production |

### Play policy notes
- **Exact alarms:** we declare `SCHEDULE_EXACT_ALARM` (user-granted). No Play declaration form is needed for it. `USE_EXACT_ALARM` is **not** declared (it is restricted to alarm/calendar apps).
- **Notifications:** the purpose is clear in onboarding. No promotional notifications.
- **Battery optimization:** we only open the settings list; we do not use `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (policy-restricted).
- **Target API:** keep `targetSdk` within Play's required level (currently the latest API minus at most one year).

## 8. Store listing assets

- App icon 512×512, feature graphic 1024×500.
- 6 phone screenshots (Home, List with subtasks, Task Detail, notification, drag-to-nest, dark mode). Capture them from the screenshot tests or a device with demo data (Settings › Debug › "Load demo data").
- Short description (80 chars): "The to-do list that keeps nudging you until it's done."
- Privacy policy URL: a GitHub Pages page stating that there is no data collection (v1).

## 9. Post-release

- Monitor Play Console → Android vitals (crashes, ANRs, **excessive wakeups**; our single-alarm design should keep wakeups low).
- Since there is no crash SDK in v1, the Play vitals crash reports are the source. A Phase 2 option is opt-in Crashlytics.
- Hotfix flow: branch `hotfix/x.y.z` from the tag → PR → tag `vX.Y.(Z+1)`.
