package com.lockgps.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var map: MapView
    private lateinit var latField: EditText
    private lateinit var lngField: EditText
    private lateinit var toggle: Button
    private lateinit var pasteBtn: Button
    private lateinit var goBtn: Button
    private lateinit var status: TextView
    private var marker: Marker? = null

    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }
    private var pendingStart = false
    private var askedNotif = false

    companion object {
        private const val REQ_PERMS = 11
        private val COORDS = Regex("""(-?\d{1,2}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)""")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid (OpenStreetMap) setup – no API key needed.
        val conf = Configuration.getInstance()
        conf.load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        conf.userAgentValue = packageName
        conf.osmdroidBasePath = File(filesDir, "osmdroid")
        conf.osmdroidTileCache = File(cacheDir, "osmdroid-tiles")

        setContentView(R.layout.activity_main)
        map = findViewById(R.id.map)
        latField = findViewById(R.id.lat)
        lngField = findViewById(R.id.lng)
        toggle = findViewById(R.id.toggle)
        pasteBtn = findViewById(R.id.paste)
        goBtn = findViewById(R.id.go)
        status = findViewById(R.id.status)

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(17.0)

        map.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (p == null) return false
                if (MockService.running) {
                    toast("Stop the lock first to move the pin")
                    return true
                }
                setPoint(p.latitude, p.longitude, center = false)
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }))

        setPoint(Prefs.lat(this), Prefs.lng(this), center = true)

        toggle.setOnClickListener { if (MockService.running) stopLock() else startLock() }
        pasteBtn.setOnClickListener { pasteCoords() }
        goBtn.setOnClickListener {
            val p = readFields()
            if (p != null) setPoint(p.first, p.second, center = true)
        }

        // App was killed while the lock was ON -> bring it back immediately.
        if (Prefs.running(this) && !MockService.running) startServiceNow()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        ui.removeCallbacks(ticker)
    }

    // ---------------------------------------------------------------- pin / fields

    private fun setPoint(lat: Double, lng: Double, center: Boolean) {
        val gp = GeoPoint(lat, lng)
        val m = marker ?: Marker(map).also {
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            it.setOnMarkerClickListener { _, _ -> true }
            map.overlays.add(it)
            marker = it
        }
        m.position = gp
        latField.setText(String.format(Locale.US, "%.6f", lat))
        lngField.setText(String.format(Locale.US, "%.6f", lng))
        if (center) map.controller.setCenter(gp)
        map.invalidate()
    }

    private fun readFields(): Pair<Double, Double>? {
        val lat = latField.text.toString().trim().replace(',', '.').toDoubleOrNull()
        val lng = lngField.text.toString().trim().replace(',', '.').toDoubleOrNull()
        if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            toast("Enter a valid latitude (-90..90) and longitude (-180..180)")
            return null
        }
        return lat to lng
    }

    private fun pasteCoords() {
        if (MockService.running) { toast("Stop the lock first"); return }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val m = COORDS.find(text)
        if (m == null) {
            toast("No coordinates found. In Google Maps, long-press a spot and copy the numbers.")
            return
        }
        setPoint(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), center = true)
    }

    // ---------------------------------------------------------------- start / stop

    private fun startLock() {
        val p = readFields() ?: return
        setPoint(p.first, p.second, center = false)

        // 1. Runtime permissions (location is required by Play Services mock mode).
        val need = mutableListOf<String>()
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif &&
            !granted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            askedNotif = true   // optional – ask once, never block the lock on it
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isNotEmpty()) {
            pendingStart = true
            requestPermissions(need.toTypedArray(), REQ_PERMS)
            return
        }

        // 2. Must be selected as the mock location app.
        if (!isMockApp()) {
            showMockSetup()
            return
        }

        // 3. Ask once to be excluded from battery optimisation (prevents Doze from killing the lock).
        askBatteryExemption()

        Prefs.savePoint(this, p.first, p.second)
        Prefs.setRunning(this, true)
        MockService.lastError = null
        startServiceNow()
        toast("Location locked")
    }

    private fun startServiceNow() {
        try {
            startForegroundService(
                Intent(this, MockService::class.java).setAction(MockService.ACTION_START)
            )
        } catch (e: Exception) {
            Prefs.setRunning(this, false)
            MockService.lastError = "Could not start: ${e.message}"
        }
        ui.postDelayed({ refresh() }, 600)
    }

    private fun stopLock() {
        Prefs.setRunning(this, false)
        stopService(Intent(this, MockService::class.java))
        toast("Lock stopped – real location restored")
        ui.postDelayed({ refresh() }, 300)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS || !pendingStart) return
        pendingStart = false
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            startLock()
        } else {
            toast("Location permission is needed")
        }
    }

    // ---------------------------------------------------------------- checks

    private fun granted(perm: String) =
        checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun isMockApp(): Boolean = try {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName)
        else
            ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }

    private fun showMockSetup() {
        AlertDialog.Builder(this)
            .setTitle("One-time setup")
            .setMessage(
                "1. Settings → About phone → tap \"Build number\" 7 times.\n" +
                    "2. Settings → Developer options → \"Select mock location app\" → LockGPS.\n" +
                    "3. Come back and tap START LOCK."
            )
            .setPositiveButton("Open Developer options") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------- UI state

    private fun refresh() {
        val on = MockService.running
        toggle.text = if (on) "STOP" else "START LOCK"
        toggle.setBackgroundColor(if (on) Color.parseColor("#C62828") else Color.parseColor("#1B5E20"))
        latField.isEnabled = !on
        lngField.isEnabled = !on
        pasteBtn.isEnabled = !on
        goBtn.isEnabled = !on

        val err = MockService.lastError
        val googleOk = MockService.fusedMockOn
        val jumps = MockService.jumps
        status.text = when {
            on -> buildString {
                append(String.format(Locale.US, "LOCKED at %.6f, %.6f", Prefs.lat(this@MainActivity), Prefs.lng(this@MainActivity)))
                append("\nGPS + network: locked ✓")
                append(if (googleOk) "\nGoogle location (WhatsApp/Maps): locked ✓"
                       else "\nGoogle location (WhatsApp/Maps): locking…")
                val checked = MockService.lastCheckMs
                if (checked == 0L) {
                    append("\nJump check: starting…")
                } else {
                    val ago = (System.currentTimeMillis() - checked) / 1000
                    append("\nJump check: $jumps jumps · checked ${ago}s ago")
                }
                MockService.lastJump?.let { append("\n⚠ Last jump: $it (auto re-locked)") }
                if (err != null) append("\n⚠ $err")
            }
            err != null -> "⚠ $err"
            else -> "Not locked. Tap the map or paste coordinates, then START LOCK."
        }
        status.setTextColor(
            when {
                on && googleOk && jumps == 0 && err == null -> Color.parseColor("#1B5E20")
                on && err == null -> Color.parseColor("#E65100")
                err != null -> Color.parseColor("#C62828")
                else -> Color.DKGRAY
            }
        )
        status.visibility = View.VISIBLE
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
