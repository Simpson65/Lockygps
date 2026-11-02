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
import com.google.android.gms.location.LocationServices
import java.util.Locale

/**
 * Foreground service that pins every location source on the phone to one point.
 *
 * Why it doesn't jump:
 *  1. Replaces the platform GPS, NETWORK and (Android 12+) FUSED providers with test providers,
 *     so real satellite / Wi-Fi / cell fixes never reach apps that use LocationManager.
 *  2. Puts Google Play Services' fused location (what WhatsApp, Google Maps etc. use) into
 *     mock mode, so it serves ONLY our point.
 *  3. Re-pushes the point 4x per second with fresh timestamps, so no app ever sees a stale fix.
 *  4. Holds a wake lock + runs as a foreground service so Doze/screen-off can't pause it.
 *  5. Remembers its state; if the system kills it, it restarts itself and re-locks.
 */
class MockService : Service() {

    companion object {
        const val ACTION_START = "com.lockgps.app.START"
        const val ACTION_STOP = "com.lockgps.app.STOP"

        private const val CHANNEL_ID = "lock"
        private const val NOTIF_ID = 7
        private const val INTERVAL_MS = 250L
        private const val ACCURACY_M = 3f

        @Volatile var running = false
            private set
        @Volatile var lastError: String? = null
        @Volatile var pushes = 0L
            private set
    }

    private lateinit var lm: LocationManager
    private var fused: FusedLocationProviderClient? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    // Replaced as a whole (never mutated) so the loop thread can iterate safely.
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

        // Must call startForeground quickly after startForegroundService().
        try {
            goForeground()
        } catch (e: Exception) {
            lastError = "Could not start background lock: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }

        // Restarted by the system after the user had already stopped it -> do nothing.
        if (!Prefs.running(this)) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }

        // Already locked (e.g. duplicate start / self-restart alarm while alive):
        // keep the existing lock untouched so there is zero gap.
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

    @SuppressLint("MissingPermission", "WrongConstant")
    private fun startMocking() {
        stopLoop()
        lastError = null
        pushes = 0

        val locked = mutableListOf<String>()
        val wanted = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= 31) wanted.add(LocationManager.FUSED_PROVIDER)

        for (name in wanted) {
            try {
                try { lm.removeTestProvider(name) } catch (_: Exception) { }
                lm.addTestProvider(
                    name,
                    false, false, false, false,   // network, satellite, cell, cost
                    true, true, true,             // altitude, speed, bearing
                    1,                            // power: low
                    1                             // accuracy: fine
                )
                lm.setTestProviderEnabled(name, true)
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

        // Google Play Services fused location – used by WhatsApp, Google Maps, most apps.
        try {
            fused = LocationServices.getFusedLocationProviderClient(this).also { client ->
                client.setMockMode(true).addOnFailureListener { e ->
                    lastError = "Play Services mock off: ${e.message}"
                }
            }
        } catch (e: Exception) {
            lastError = "Play Services mock off: ${e.message}"
        }

        acquireWakeLock()

        val t = HandlerThread("lock-loop").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        running = true

        h.post(object : Runnable {
            override fun run() {
                if (!running) return
                push()
                h.postDelayed(this, INTERVAL_MS)
            }
        })
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
        stopLoop()
        val list = providers
        providers = emptyList()
        for (name in list) {
            try { lm.setTestProviderEnabled(name, false) } catch (_: Exception) { }
            try { lm.removeTestProvider(name) } catch (_: Exception) { }
        }
        try { fused?.setMockMode(false) } catch (_: Exception) { }
        fused = null
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
        // Some phones kill the service when the app is swiped away. Schedule a quick self-restart.
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
