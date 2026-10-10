package com.litetv.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Kotlin extension utilities.
 * Keep this minimal — each function here should justify its existence.
 */

/**
 * Check if the device has an active network connection.
 */
fun Context.isNetworkAvailable(): Boolean {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/**
 * Safe string truncation that doesn't crash on short strings.
 */
fun String.truncate(maxLength: Int, suffix: String = "…"): String {
    return if (length <= maxLength) this else take(maxLength - suffix.length) + suffix
}
