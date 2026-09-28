# How to build and test Nudge

This guide covers two things:

- getting an up-to-date APK (the app file you install on a phone);
- testing the app on this Mac with a virtual phone (the "emulator").

No Android experience is needed. Run every command in **Terminal**.

---

## 1. One-time setup

The build tools (Java 17 and the Android SDK) are already installed on this Mac. You only need to tell
Terminal where they are. Run this once:

```bash
cat >> ~/.zshrc <<'EOF'
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator
EOF
source ~/.zshrc
```

Check it worked. Both commands should print a version number:

```bash
java -version
adb version
```

> On a different Mac, install the tools first with `brew install openjdk@17`, then install Android
> Studio (it installs the Android SDK and the emulator).

---

## 2. Get the latest code

```bash
cd /Users/macbookpro/UtsavJain/SelfProjects/ReminderApp/Nedge-Remainder-App
git checkout main
git pull
```

---

## 3. Build an updated APK

```bash
./scripts/build-apk.sh
```

When it finishes it prints where the file is, for example:

```
✔ APK ready: .../Nedge-Remainder-App/dist/Nudge-1.0.0-debug-20260928-1619.apk (22M)
```

- Every build is saved in the **`dist/`** folder with the date and time in its name, so the newest file
  is the latest version.
- This is the **test build**. It includes **Settings › Debug tools** (demo data, time travel), which is
  what you want while testing.
- The first build takes a few minutes; later builds take well under a minute.

For a smaller, non-debuggable build for everyday use, see [section 6](#6-release-apk-for-everyday-use-optional).

---

## 4. Install the APK on your Android phone

The phone needs Android 8.0 or newer.

### Option A: USB cable (easiest for repeated updates)

1. **Turn on Developer options.** On the phone, go to **Settings › About phone** and tap **Build number**
   7 times.
   - Xiaomi: tap "MIUI/HyperOS version".
   - Samsung: it's under "Software information".
2. **Turn on USB debugging.** Go to **Settings › System › Developer options** and switch it on.
   On Xiaomi, also turn on **Install via USB**.
3. **Connect the phone** to the Mac and tap **Allow** on the "Allow USB debugging?" prompt.
4. **Check the connection.** Run `adb devices`; it should list one line ending in `device`.
5. **Build and install in one step:**
   ```bash
   ./scripts/build-apk.sh --install
   ```
6. When you've finished testing, you can turn **USB debugging** off again.

### Option B: no cable

1. Upload the APK from `dist/` to Google Drive, or email it to yourself.
2. Open it on the phone and tap **Install**.
3. If asked, allow "Install unknown apps" for Drive or your email app.
4. If Play Protect warns you, tap **More details › Install anyway**. This is normal for apps that don't
   come from the Play Store.

### Updating

Install the new APK the same way. Your tasks are kept.

If the phone says **"App not installed"** or shows
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the old copy was signed on a different computer or with a
different key. Uninstall Nudge from the phone and install again. This deletes its tasks; export them
first from **Settings › Data › Export** if you want to keep them.

### First run on the phone

1. Go through the 3 welcome pages and tap **Allow** on both permission cards.
2. On **Xiaomi, Oppo, Vivo, Realme, OnePlus, Samsung or Huawei** phones, open **Settings › Reminders ›
   Reminder health** and follow the battery tips. Otherwise the phone may delay or block reminders.

---

## 5. Test the app on this Mac (virtual phone)

### Start it

```bash
cd /Users/macbookpro/UtsavJain/SelfProjects/ReminderApp/Nedge-Remainder-App
./scripts/run-on-emulator.sh
```

This builds the app, opens a **virtual phone window**, installs Nudge and launches it (about a minute).
Run the same command again after any code change to reinstall the latest version.

### How to use the virtual phone

| On a phone | In the emulator window |
|---|---|
| Tap | Click |
| Swipe or scroll | Click and drag |
| Long-press (e.g. to drag a task onto another) | Click and **hold**, then move |
| Type | Use your Mac keyboard |
| Back / Home | Buttons on the emulator's side toolbar |
| Notification shade | Drag down from the very top of the screen |

### Quick setup inside the app

1. On the permission page, tap **Allow** on both cards, then **Start**.
2. Load sample lists and tasks: **Settings (gear icon) › scroll down › Debug tools › Load demo data**.

### Test reminders without waiting

- In **Settings › Debug tools**, tap **Time travel +10 min** or **+1 h**. This moves the app's clock
  forward and fires any reminders that are due.
- Pull down the notification shade to see them, and try the **Done** and **Snooze** buttons.
- Nothing fires from 10 PM to 8 AM (quiet hours). To test at night, turn quiet hours off in
  **Settings › Reminders**.
- **Reset time** in Debug tools puts the app's clock back to normal.

### Stop it

Close the emulator window, or run `adb emu kill`.

### Things worth trying

- [ ] Create a list, pick a color and an emoji; the list screen takes that color.
- [ ] **+** → type a task → press Enter → type another. The sheet stays open for quick entry.
- [ ] Pick **Urgent**; the "Reminder" chip changes to "Every 10 min".
- [ ] Tap a task to complete it; it animates into "Completed". Tap **Undo** on the message.
- [ ] Swipe a task right (complete) and left (delete).
- [ ] Long-press a task and drop it onto the middle of another task. It becomes a subtask.
- [ ] Open a task with **›**: change the progress slider, add a due date, snooze, add subtasks.
- [ ] Search (magnifier on Home), the **Today** and **All tasks** cards, dark theme in Settings.
- [ ] Export your data in **Settings › Data**, then import it again.

---

## 6. Release APK for everyday use (optional)

The test build is "debuggable" and larger (about 22 MB). The release build is about 2.7 MB, faster, and
has no Debug tools. It must be signed with **your own key**, which you create once.

1. **Create the key.** You'll be asked for a password and your name. Remember the password.
   ```bash
   cd /Users/macbookpro/UtsavJain/SelfProjects/ReminderApp/Nedge-Remainder-App
   mkdir -p keystore
   keytool -genkeypair -v -keystore keystore/nudge-upload.jks -alias nudge -keyalg RSA -keysize 4096 -validity 10000
   ```
2. **Tell the build where the key is.** Replace `YOUR_PASSWORD` (twice) with the password you chose:
   ```bash
   cat >> local.properties <<'EOF'
   KEYSTORE_PATH=keystore/nudge-upload.jks
   KEYSTORE_PASSWORD=YOUR_PASSWORD
   KEY_ALIAS=nudge
   KEY_PASSWORD=YOUR_PASSWORD
   EOF
   ```
3. **Build it** (add `--install` to install over USB as well):
   ```bash
   ./scripts/build-apk.sh release
   ```
4. **Back up `keystore/nudge-upload.jks` and the password**, for example in a password manager. Every
   future update must be signed with the same key. If you lose it, you'll have to uninstall and
   reinstall, which loses your data.

`keystore/`, `local.properties` and `dist/` are never uploaded to GitHub.

The test build and the release build can be installed side by side; both show as "Nudge". The test
build is the one whose Settings has **Debug tools** at the bottom.

---

## 7. Troubleshooting

| Problem | Fix |
|---|---|
| `command not found: adb` or `java` | Do [section 1](#1-one-time-setup), then open a new Terminal window. |
| `adb devices` shows nothing, or `unauthorized` | Unplug and replug the cable, and tap **Allow** on the phone. Try another cable; some only charge. |
| Emulator window is black or doesn't open | Run `adb emu kill`, wait 10 seconds, and run `./scripts/run-on-emulator.sh` again. |
| `No signing key configured` | You ran `build-apk.sh release` before doing [section 6](#6-release-apk-for-everyday-use-optional). |
| "App not installed" on the phone | See [Updating](#updating). |
| Reminders arrive late on the phone | Open **Settings › Reminders › Reminder health** in the app and fix each item it lists. |
| Build fails after `git pull` | Run `./gradlew clean`, then run the build again. If it still fails, copy the error text and ask. |
