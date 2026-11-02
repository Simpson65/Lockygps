package com.lockgps.app

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that pins every location source on the phone to one point.
 *
 * Anti-jump layers:
 *  1. Platform GPS, NETWORK and (Android 12+) FUSED providers replaced by test providers.
 *  2. Google Play Services fused location (WhatsApp, Google Maps…) put in mock mode.
 *  3. Point re-pushed 5x per second with fresh timestamps.
 *  4. Every 3 s: mock mode + providers re-asserted (Play Services can silently drop mock mode).
 *  5. A removed provider is re-added on the spot.
 *  6. Jump watchdog: listens to Play Services like WhatsApp does. If any fix is off the pin,
 *     it instantly re-locks and logs the jump so you can see it in the app.
 *  7. Wake lock + foreground service + self-restart.
 */
class MockService : Service() {

    companion object {
        const val ACTION_START = "com.lockgps.app.START"
        const val ACTION_STOP = "com.lockgps.app.STOP"

        private const val CHANNEL_ID = "lock"
        private const val NOTIF_ID = 7
        private const val INTERVAL_MS = 200L
        private const val REASSERT_EVERY = 15          // ticks -> every 3 s
        private const val ACCURACY_M = 3f
        private const val JUMP_M = 30f                 // farther than this from the pin = jump
        private const val WARMUP_MS = 4000L            // ignore watchdog right after start

        @Volatile var running = false
            private set
        @Volatile var lastError: String? = null
        @Volatile var pushes = 0L
            private set
        @Volatile var fusedMockOn = false
            private set
        @Volatile var jumps = 0
            private set
        @Volatile var lastJump: String? = null
            private set
        @Volatile var lastCheckMs = 0L
            private set
    }

    private lateinit var lm: LocationManager
    private var fused: FusedLocationProviderClient? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var watching = false
    private var startedAt = 0L
    private var ticks = 0L

    // Replaced as a whole (never mutated) so it can be read safely from any thread.
    @Volatile private var providers: List<String> = emptyList()

    @Volatile private var lat = 0.0
    @Volatile private var lng = 0.0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setRunning(this, false)
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            goForeground()
        } catch (e: Exception) {
            lastError = "Could not start background lock: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Prefs.running(this)) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }

        // Already locked: keep the existing lock untouched so there is zero gap.
        if (running) {
            updateNotification()
            return START_STICKY
        }

        lat = Prefs.lat(this)
        lng = Prefs.lng(this)
        updateNotification()
        startMocking()
        return START_STICKY
    }

    // ---------------------------------------------------------------- mocking

    private fun startMocking() {
        stopLoop()
        lastError = null
        pushes = 0
        jumps = 0
        lastJump = null
        lastCheckMs = 0L
        fusedMockOn = false
        ticks = 0
        startedAt = SystemClock.elapsedRealtime()

        val locked = mutableListOf<String>()
        for (name in wantedProviders()) {
            try {
                try { lm.removeTestProvider(name) } catch (_: Exception) { }
                addProvider(name)
                locked.add(name)
            } catch (e: SecurityException) {
                fail("LockGPS is not selected as the mock location app (Developer options).")
                return
            } catch (_: Exception) {
                // Provider not available on this phone – skip it.
            }
        }
        if (locked.isEmpty()) {
            fail("No location provider could be locked on this phone.")
            return
        }
        providers = locked.toList()

        try {
            fused = LocationServices.getFusedLocationProviderClient(this)
        } catch (e: Exception) {
            lastError = "Google Play Services not available: ${e.message}"
        }

        acquireWakeLock()

        val t = HandlerThread("lock-loop").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        running = true

        h.post {
            assertMockMode()
            push()
            startWatchdog()
        }
        h.post(object : Runnable {
            override fun run() {
                if (!running) return
                push()
                ticks++
                if (ticks % REASSERT_EVERY == 0L) reassert()
                h.postDelayed(this, INTERVAL_MS)
            }
        })
    }

    private fun wantedProviders(): List<String> {
        val list = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= 31) list.add(LocationManager.FUSED_PROVIDER)
        return list
    }

    @SuppressLint("WrongConstant")
    private fun addProvider(name: String) {
        lm.addTestProvider(
            name,
            false, false, false, false,   // network, satellite, cell, cost
            true, true, true,             // altitude, speed, bearing
            1,                            // power: low
            1                             // accuracy: fine
        )
        lm.setTestProviderEnabled(name, true)
    }

    @SuppressLint("MissingPermission")
    private fun assertMockMode() {
        val client = fused ?: return
        try {
            client.setMockMode(true)
                .addOnSuccessListener { fusedMockOn = true }
                .addOnFailureListener { e ->
                    fusedMockOn = false
                    lastError = "Google location not locked: ${e.message}"
                }
        } catch (e: Exception) {
            fusedMockOn = false
            lastError = "Google location not locked: ${e.message}"
        }
    }

    /** Periodic re-lock: providers enabled + Play Services mock mode on. */
    private fun reassert() {
        for (name in providers) {
            try { lm.setTestProviderEnabled(name, true) } catch (_: SecurityException) {
                fail("Mock location permission was removed."); return
            } catch (_: Exception) {
                try { addProvider(name) } catch (_: Exception) { }
            }
        }
        assertMockMode()
    }

    @SuppressLint("MissingPermission")
    private fun push() {
        val list = providers
        for (name in list) {
            try {
                lm.setTestProviderLocation(name, buildLocation(name))
            } catch (e: SecurityException) {
                fail("Mock location permission was removed.")
                return
            } catch (e: IllegalArgumentException) {
                // Provider got removed by the system – put it straight back.
                try {
                    addProvider(name)
                    lm.setTestProviderLocation(name, buildLocation(name))
                } catch (_: Exception) { }
            } catch (_: Exception) { }
        }
        try {
            fused?.setMockLocation(buildLocation("fused"))
        } catch (_: Exception) { }
        pushes++
    }

    private fun buildLocation(provider: String): Location = Location(provider).apply {
        latitude = lat
        longitude = lng
        accuracy = ACCURACY_M
        speed = 0f
        bearing = 0f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        speedAccuracyMetersPerSecond = 0.1f
        bearingAccuracyDegrees = 0.1f
    }

    // ---------------------------------------------------------------- jump watchdog

    private val watchdog = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) check(loc)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startWatchdog() {
        val client = fused ?: return
        val looper = thread?.looper ?: return
        try {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L)
                .build()
            client.requestLocationUpdates(req, watchdog, looper)
            watching = true
        } catch (_: Exception) { }
    }

    private fun check(loc: Location) {
        lastCheckMs = System.currentTimeMillis()
        if (SystemClock.elapsedRealtime() - startedAt < WARMUP_MS) return
        val d = FloatArray(1)
        Location.distanceBetween(lat, lng, loc.latitude, loc.longitude, d)
        if (d[0] > JUMP_M) {
            jumps++
            val dist = if (d[0] >= 1000f) String.format(Locale.US, "%.1f km", d[0] / 1000f)
            else String.format(Locale.US, "%.0f m", d[0])
            lastJump = "$dist off at " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            // Re-lock immediately.
            reassert()
            push()
        }
    }

    // ---------------------------------------------------------------- stop

    private fun fail(msg: String) {
        lastError = msg
        running = false
        Prefs.setRunning(this, false)
        Handler(mainLooper).post {
            shutdown()
            stopSelf()
        }
    }

    private fun stopLoop() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun shutdown() {
        if (watching) {
            try { fused?.removeLocationUpdates(watchdog) } catch (_: Exception) { }
            watching = false
        }
        stopLoop()
        val list = providers
        providers = emptyList()
        for (name in list) {
            try { lm.setTestProviderEnabled(name, false) } catch (_: Exception) { }
            try { lm.removeTestProvider(name) } catch (_: Exception) { }
        }
        try { fused?.setMockMode(false) } catch (_: Exception) { }
        fused = null
        fusedMockOn = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LockGPS::lock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!Prefs.running(this)) return
        try {
            val pi = PendingIntent.getForegroundService(
                this, 2,
                Intent(this, MockService::class.java).setAction(ACTION_START),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1000, pi)
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notification

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Location lock", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MockService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (Prefs.running(this))
            String.format(Locale.US, "%.6f, %.6f", Prefs.lat(this), Prefs.lng(this))
        else "Starting…"

        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pin)
            .setContentTitle("Location locked")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_pin), "STOP", stop
                ).build()
            )
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) { }
    }
}
