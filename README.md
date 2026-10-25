# HomeBase — a fast, clean home screen for your TCL Google TV

HomeBase replaces the Google TV home screen with a plain grid of the apps you choose.
It has no recommendations, ads or background loading, which matters on the 43V6B's 2 GB of RAM.

**What it does**
- Rows: Continue watching, Streaming, Live TV, Inputs (HDMI/AV + TV), Music & radio, Games, Apps and Tools. Hold OK on an app to move it to another row.
- Greeting with your name, the time of day and live weather.
- Backgrounds: animated aurora that follows the time of day, Bing photo of the day, or your own photos from the `wallpapers/` folder.
- Sleep timer and Screen off, both in the Tools row. These need the helper service; see below.

- A clock and date, with a row of your pinned apps, using each app's TV banner.
- **All apps**: every installed app. Hold OK on an app to pin it to Home.
- On Home, hold OK (or press Menu) on a tile to move it left or right, remove it, open App info, or uninstall it.
- **Launcher options**: check for updates, set the update source, toggle the Home-button takeover, or reset Home.
- **Home-button takeover**: Google TV hard-wires the Home button to its own launcher. A small accessibility service sees the stock home screen open and switches to HomeBase straight away, including after a reboot. It only listens to the stock launcher's packages and reads nothing on screen.
- **Self-update**: once a day, or on demand, it checks `version.json` and installs a newer build after you confirm on the TV.

---

## 1. One-time setup

### Option A — Build in the cloud with GitHub (recommended, makes updates easy)
1. Create a **public** GitHub repository called `HomeBase`. It must be public so the TV can download releases without signing in.
2. Upload this folder to it, **except** `keystore/` and `keystore.properties`. The `.gitignore` already excludes them.
3. In the repo, go to **Settings → Secrets and variables → Actions → New repository secret** and add the four secrets listed in `keystore/github-secrets.txt`: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.
4. Go to **Actions → Build HomeBase → Run workflow**. After about 4 minutes a release appears containing `HomeBase.apk` and `version.json`.
5. Download `HomeBase.apk` and install it as described in step 2 below.

### Option B — Build on your PC
1. Install **Android Studio**, open this folder, and let it sync.
2. Use **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. Your local `keystore.properties` signs the build automatically.
   - Command line alternative: `gradlew assembleRelease`, which writes `app/build/outputs/apk/release/app-release.apk`.

> **Back up `keystore/release.jks` and `keystore.properties`.** Android only accepts an update if it is signed with the same key. If you lose the key, you would have to uninstall HomeBase and set it up again.

## 2. Put it on the TV

On the TV, enable developer mode: **Settings → System → About → click "Android TV OS build" 7 times**. Then go to **Developer options** and turn on **USB debugging**.

From the HomeBase folder on your PC, with `adb` installed:

```bat
scripts\install-to-tv.bat 192.168.1.50 HomeBase.apk      (Windows)
./scripts/install-to-tv.sh 192.168.1.50 HomeBase.apk     (Mac/Linux)
```

Accept the **Allow USB debugging?** prompt on the TV the first time. The script does four things:
- installs or updates the app,
- lets HomeBase install its own updates,
- turns on the Home-button service,
- opens HomeBase.

Press **Home** on the remote to confirm it lands on HomeBase.

<details><summary>The same thing as individual adb commands</summary>

```bash
adb connect 192.168.1.50:5555
adb install -r HomeBase.apk
adb shell appops set com.homebase.tvlauncher REQUEST_INSTALL_PACKAGES allow
adb shell settings put secure enabled_accessibility_services com.homebase.tvlauncher/com.homebase.tvlauncher.HomeWatcherService
adb shell settings put secure accessibility_enabled 1
```
The `enabled_accessibility_services` line replaces any other accessibility services you have enabled. The install scripts append instead.
</details>

## 3. Updating HomeBase

**With GitHub (Option A):** edit the code, commit, and push to `main`. The workflow builds a new numbered release. Within a day the TV offers it, or you can go to **Launcher options → Check for updates** right away and press **Install**. No PC or adb is needed.

**Without GitHub:** raise `versionCode` in `gradle.properties`, rebuild, then run the install script again. `adb install -r` keeps your pinned apps.

**Your own server instead of GitHub:** put `HomeBase.apk` and a `version.json` like the one below anywhere reachable over HTTPS. Then on the TV go to **Launcher options → Update source** and enter the URL of `version.json`.
```json
{ "versionCode": 12, "versionName": "1.0.12", "apkUrl": "https://example.com/HomeBase.apk" }
```

## 4. Customising

| What | Where |
|---|---|
| Apps pinned on first run | `DEFAULT_PINS` in `AppRepo.kt` |
| Tiles per row | `COLUMNS` in `MainActivity.kt` / `AllAppsActivity.kt`, plus `numColumns` in the matching layout |
| Colours | `res/values/colors.xml`, `res/drawable/bg_home.xml` |
| Text | `res/values/strings.xml` |
| Clock format | `res/layout/activity_main.xml` (`format24Hour`) |

## 5. Removing the TCL / Google bloat (optional, reversible)

```bash
adb shell pm list packages | grep -iE "tcl|netflix|amazon|disney"   # see what's there
adb shell pm uninstall -k --user 0 <package>                        # remove for your user
adb shell cmd package install-existing <package>                    # bring it back
```
Leave anything with `settings`, `input`, `tv.provider`, `framework`, `systemui`, `setupwraith` or `launcherx` in its name alone. HomeBase relies on the stock launcher still existing so it can take over from it.

## 6. Turning on the helper service (for Sleep timer / Screen off)
Run this once from your PC:
```powershell
adb shell settings put secure enabled_accessibility_services com.homebase.tvlauncher/com.homebase.tvlauncher.HomeWatcherService
adb shell settings put secure accessibility_enabled 1
```

## 7. Going back to the stock home screen
Go to **Launcher options** and switch the Home button to **OFF**. To remove HomeBase completely, run `adb uninstall com.homebase.tvlauncher`.
