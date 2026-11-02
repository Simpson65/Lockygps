# LockGPS – Android location lock

Pins the phone's location to one point. Stays fixed until you tap STOP.

Locks every source apps read from:
- Platform GPS, Network and Fused providers (test providers replace the real ones)
- Google Play Services fused location (used by WhatsApp, Google Maps, most apps) in mock mode
- Point re-sent 4×/second with fresh timestamps
- Foreground service + wake lock, so screen-off / Doze can't pause it
- Self-restarts if the phone kills it; reopening the app resumes the lock

## 1. Get the APK

**Option A – GitHub (free, in browser)**
1. github.com → New repository (Private is fine).
2. Upload every file from this zip, keeping folders. Include the `.github` folder.
   If `.github` doesn't upload: Add file → Create new file → name it
   `.github/workflows/build.yml` → paste the contents of that file.
3. Actions tab → **Build APK** → Run workflow. Takes ~5 min.
4. Open the finished run → Artifacts → **LockGPS-apk** → download → unzip → `app-debug.apk`.

**Option B – Android Studio**
Open this folder → Build → Build APK(s).

## 2. Phone setup (one time)
1. Install the APK (allow "Install unknown apps").
2. Settings → About phone → tap **Build number** 7 times.
3. Settings → Developer options → **Select mock location app** → LockGPS.
4. Open LockGPS → allow Location and Notifications → allow "ignore battery optimisation".
5. Settings → Apps → LockGPS → Battery → **Unrestricted**.
   Xiaomi / Redmi / Realme / Oppo / Vivo / OnePlus: also turn **Autostart ON** and lock the app in Recents.

## 3. Use
1. Tap the map, or paste coordinates (Google Maps: long-press a spot → copy the numbers).
2. Tap **START LOCK**.
3. Then open WhatsApp / Maps and start live location.
4. To end: **STOP** in the app or in the notification. Real location returns.

## Zero-jump checklist
- Lock first, start sharing after.
- Settings → Location → Location services: **Wi-Fi scanning OFF**, **Bluetooth scanning OFF**, **Google Location Accuracy OFF**.
- Never force-stop LockGPS while sharing.
- The pin can't be moved while locked, so stray taps can't make it jump.

## Limits
- Android 8+ only. iPhone does not allow mock-location apps.
- Apps can see a "mock location" flag; a few apps may reject it.
- After a phone restart, open LockGPS once – it re-locks automatically.
