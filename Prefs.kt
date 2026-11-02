package com.lockgps.app

import android.content.Context

/** Stores the locked point and whether the lock should be running. Survives app/process restarts. */
object Prefs {
    private const val FILE = "lock"
    private const val DEFAULT_LAT = 28.612912   // India Gate, New Delhi
    private const val DEFAULT_LNG = 77.229510

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun lat(c: Context): Double = sp(c).getString("lat", null)?.toDoubleOrNull() ?: DEFAULT_LAT
    fun lng(c: Context): Double = sp(c).getString("lng", null)?.toDoubleOrNull() ?: DEFAULT_LNG

    fun savePoint(c: Context, lat: Double, lng: Double) {
        sp(c).edit().putString("lat", lat.toString()).putString("lng", lng.toString()).commit()
    }

    fun running(c: Context): Boolean = sp(c).getBoolean("running", false)

    fun setRunning(c: Context, value: Boolean) {
        sp(c).edit().putBoolean("running", value).commit()
    }
}
