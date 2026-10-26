# STEEL BALL RUN (Android app)

Your player, packaged as a real Android app:

- Fixed full-screen layout: no browser bar, no page bounce, no pull-to-refresh, no zoom. Only the song list scrolls.
- Steel Ball Run artwork = app icon, opening screen, default wallpaper and default cover in the notification.
- Media notification / lock screen card like a normal music app: cover, title, seek bar, ♥ / previous / play-pause / next.
  Keeps playing with the screen off. Back button sends the app to the background instead of closing it.

## Get the APK with GitHub (about 5 minutes, free)

**From a phone (2 uploads):**
1. Create a new GitHub repository (private is fine).
2. *Add file → Upload files* → upload `SteelBallRun.zip` as it is (do not unzip it).
3. *Add file → Create new file* → name it `.github/workflows/build-apk.yml` (typing the slashes creates the folders)
   → paste the text of `build-apk.yml` from this zip → *Commit*.

**From a computer:** unzip, then drag everything (including the hidden `.github` folder) into *Add file → Upload files* → Commit.

Then open the **Actions** tab. When the build turns green (3-6 min), open **Releases** on the repo's main page,
download `STEEL-BALL-RUN.apk` and install it (allow "install unknown apps" for your browser when Android asks).
Every new commit builds a new APK.

## First run
- Allow notifications when asked (needed for the lock-screen controls).
- Settings (gear) → add songs. "Scan Internal Storage" opens a folder picker: choose your Music folder.
- Export / save buttons write to Downloads/STEEL BALL RUN.

## Project layout
- `app/src/main/assets/index.html` - the player itself (your original page, adapted).
- `app/src/main/java/.../MainActivity.java` - fixed full-screen WebView shell.
- `app/src/main/java/.../MediaService.java` - media notification + lock-screen controls.
