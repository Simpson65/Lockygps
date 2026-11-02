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
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var map: MapView
    private lateinit var searchField: EditText
    private lateinit var searchBtn: ImageButton
    private lateinit var latField: EditText
    private lateinit var lngField: EditText
    private lateinit var toggle: Button
    private lateinit var pasteBtn: Button
    private lateinit var goBtn: Button
    private lateinit var status: TextView
    private var marker: Marker? = null

    /** Name of the place the pin is on (from search / favourites), used as default favourite name. */
    private var placeName: String? = null
    private var searching = false

    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }
    private var pendingStart = false
    private var askedNotif = false

    private data class Place(val name: String, val lat: Double, val lng: Double)

    companion object {
        private const val REQ_PERMS = 11
        private const val MENU_FAVS = 1
        private const val MENU_SAVE = 2
        private const val MENU_HELP = 3
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
        searchField = findViewById(R.id.search)
        searchBtn = findViewById(R.id.searchBtn)
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
                choose(p.latitude, p.longitude, null, center = false)
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }))

        setPoint(Prefs.lat(this), Prefs.lng(this), center = true)

        toggle.setOnClickListener { if (MockService.running) stopLock() else startLock() }
        pasteBtn.setOnClickListener { pasteCoords() }
        goBtn.setOnClickListener {
            val p = readFields()
            if (p != null) {
                placeName = null
                setPoint(p.first, p.second, center = true)
            }
        }
        searchBtn.setOnClickListener { doSearch() }
        searchField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(); true
            } else false
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

    // ---------------------------------------------------------------- menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_FAVS, 0, "Favourites")
            .setIcon(R.drawable.ic_star)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, MENU_SAVE, 1, "Save pin to favourites")
        menu.add(0, MENU_HELP, 2, "Setup & no-jump tips")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_FAVS -> { showFavourites(); true }
        MENU_SAVE -> { saveFavourite(); true }
        MENU_HELP -> { showHelp(); true }
        else -> super.onOptionsItemSelected(item)
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
        if (center) {
            map.controller.setZoom(17.0)
            map.controller.setCenter(gp)
        }
        map.invalidate()
    }

    /**
     * Every new spot (map tap, search, favourite, paste) comes through here.
     * While locked it asks first, then moves the lock straight to the new spot –
     * the real location is never exposed in between.
     */
    private fun choose(lat: Double, lng: Double, name: String?, center: Boolean = true) {
        if (!MockService.running) {
            placeName = name
            setPoint(lat, lng, center)
            return
        }
        val label = name ?: String.format(Locale.US, "%.6f, %.6f", lat, lng)
        AlertDialog.Builder(this)
            .setTitle("Move lock here?")
            .setMessage("$label\n\nLocation moves straight to this spot. Your real location is never shown.")
            .setPositiveButton("Move") { _, _ ->
                placeName = name
                setPoint(lat, lng, center)
                moveLock(lat, lng)
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val m = COORDS.find(text)
        if (m == null) {
            toast("No coordinates found. In Google Maps, long-press a spot and copy the numbers.")
            return
        }
        choose(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), null)
    }

    // ---------------------------------------------------------------- search

    private fun doSearch() {
        val q = searchField.text.toString().trim()
        if (q.isEmpty()) { toast("Type a place, area or address"); return }
        hideKeyboard()

        // Coordinates or a Google Maps link typed/pasted into search -> use directly.
        COORDS.find(q)?.let { m ->
            choose(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), null)
            return
        }

        if (searching) return
        searching = true
        searchBtn.isEnabled = false
        toast("Searching…")
        Thread {
            val results = try { search(q) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                searching = false
                searchBtn.isEnabled = true
                if (!isFinishing && !isDestroyed) showResults(q, results)
            }
        }.start()
    }

    /** Google geocoder built into the phone first, then OpenStreetMap. Runs on a background thread. */
    private fun search(q: String): List<Place> {
        val out = mutableListOf<Place>()

        if (Geocoder.isPresent()) {
            try {
                @Suppress("DEPRECATION")
                val list = Geocoder(this, Locale.getDefault()).getFromLocationName(q, 6)
                list?.forEach { a ->
                    if (a.hasLatitude() && a.hasLongitude()) {
                        val name = a.getAddressLine(0) ?: a.featureName ?: q
                        out.add(Place(name, a.latitude, a.longitude))
                    }
                }
            } catch (_: Exception) { }
        }

        try {
            val url = URL(
                "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&q=" +
                    URLEncoder.encode(q, "UTF-8")
            )
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "LockGPS/1.2 (Android personal app)")
                conn.setRequestProperty("Accept-Language", Locale.getDefault().language + ",en")
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val lat = o.optString("lat").toDoubleOrNull() ?: continue
                        val lng = o.optString("lon").toDoubleOrNull() ?: continue
                        val name = o.optString("display_name", q)
                        if (out.none { near(it, lat, lng) }) out.add(Place(name, lat, lng))
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) { }

        return out
    }

    private fun near(p: Place, lat: Double, lng: Double): Boolean {
        val d = FloatArray(1)
        Location.distanceBetween(p.lat, p.lng, lat, lng, d)
        return d[0] < 75f
    }

    private fun showResults(q: String, results: List<Place>) {
        if (results.isEmpty()) {
            toast("Nothing found for \"$q\". Add the city name or check internet.")
            return
        }
        val labels = results.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Results for \"$q\"")
            .setItems(labels) { _, which ->
                val p = results[which]
                choose(p.lat, p.lng, p.name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchField.windowToken, 0)
        searchField.clearFocus()
    }

    // ---------------------------------------------------------------- favourites

    private fun showFavourites() {
        val favs = Prefs.favs(this)
        if (favs.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("★ Favourites")
                .setMessage("No favourites yet.\nPick a spot on the map or search, then save it.")
                .setPositiveButton("＋ Save current pin") { _, _ -> saveFavourite() }
                .setNegativeButton("Close", null)
                .show()
            return
        }
        val list = ListView(this)
        list.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            favs.map { "★  ${it.name}\n" + String.format(Locale.US, "     %.5f, %.5f", it.lat, it.lng) }
        )
        val dlg = AlertDialog.Builder(this)
            .setTitle("★ Favourites  (long-press to edit)")
            .setView(list)
            .setPositiveButton("＋ Save current pin") { _, _ -> saveFavourite() }
            .setNegativeButton("Close", null)
            .create()
        list.setOnItemClickListener { _, _, pos, _ ->
            dlg.dismiss()
            val f = favs[pos]
            choose(f.lat, f.lng, f.name)
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            dlg.dismiss()
            editFavourite(pos)
            true
        }
        dlg.show()
    }

    private fun editFavourite(pos: Int) {
        val favs = Prefs.favs(this)
        val f = favs.getOrNull(pos) ?: return
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(arrayOf("Use this spot", "Rename", "Delete")) { _, which ->
                when (which) {
                    0 -> choose(f.lat, f.lng, f.name)
                    1 -> askName("Rename favourite", f.name) { n ->
                        favs[pos] = f.copy(name = n)
                        Prefs.saveFavs(this, favs)
                        showFavourites()
                    }
                    2 -> {
                        favs.removeAt(pos)
                        Prefs.saveFavs(this, favs)
                        toast("Deleted")
                        showFavourites()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveFavourite() {
        val p = readFields() ?: return
        val def = placeName?.substringBefore(",")?.trim()?.take(40)?.ifEmpty { null }
            ?: String.format(Locale.US, "Spot %.4f, %.4f", p.first, p.second)
        askName("Save favourite", def) { n ->
            val favs = Prefs.favs(this)
            favs.add(0, Prefs.Fav(n, p.first, p.second))
            Prefs.saveFavs(this, favs)
            toast("Saved ★ $n")
        }
    }

    private fun askName(title: String, default: String, onOk: (String) -> Unit) {
        val input = EditText(this)
        input.setText(default)
        input.setSingleLine(true)
        input.setSelectAllOnFocus(true)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this)
        box.setPadding(pad, pad / 2, pad, 0)
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                onOk(input.text.toString().trim().ifEmpty { default })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- start / stop / move

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

    private fun moveLock(lat: Double, lng: Double) {
        Prefs.savePoint(this, lat, lng)
        try {
            startService(Intent(this, MockService::class.java).setAction(MockService.ACTION_MOVE))
            toast("Lock moved")
        } catch (e: Exception) {
            toast("Could not move: ${e.message}")
        }
        ui.postDelayed({ refresh() }, 300)
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

    // ---------------------------------------------------------------- checks / help

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

    private fun openDeveloperOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
        }
    }

    private fun showMockSetup() {
        AlertDialog.Builder(this)
            .setTitle("One-time setup")
            .setMessage(
                "1. Settings → About phone → tap \"Build number\" 7 times.\n" +
                    "2. Settings → Developer options → \"Select mock location app\" → LockGPS.\n" +
                    "3. Come back and tap START LOCK."
            )
            .setPositiveButton("Open Developer options") { _, _ -> openDeveloperOptions() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("Setup & no-jump tips")
            .setMessage(
                "SETUP (once)\n" +
                    "• About phone → tap Build number 7×.\n" +
                    "• Developer options → Select mock location app → LockGPS.\n" +
                    "• Apps → LockGPS → Battery → Unrestricted. Xiaomi/Realme/Oppo/Vivo: Autostart ON.\n\n" +
                    "NO JUMPS\n" +
                    "• Location → Location services → Google Location Accuracy OFF, Wi-Fi scanning OFF, Bluetooth scanning OFF.\n" +
                    "• Lock first. Wait for \"Google location: locked ✓\". Then share.\n" +
                    "• To change spot while sharing: pick a new spot / favourite → Move. Don't stop the lock.\n" +
                    "• Stop sharing first, then stop the lock."
            )
            .setPositiveButton("Developer options") { _, _ -> openDeveloperOptions() }
            .setNeutralButton("Location settings") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) { }
            }
            .setNegativeButton("Close", null)
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
            else -> "Not locked. Search, tap the map or pick a ★ favourite, then START LOCK."
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
