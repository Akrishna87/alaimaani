package io.github.akrishna87.signal

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** RSSI for the Wi-Fi network this phone is currently connected to. */
data class WifiReading(
    val rssiDbm: Int,
    val frequencyMhz: Int?,
    val linkSpeedMbps: Int?,
    val atMillis: Long,
)

/** A named room or position measured on the connected Wi-Fi network. */
data class WifiSpot(
    val id: Long,
    val name: String,
    val atMillis: Long,
    val averageDbm: Int,
    val minimumDbm: Int,
    val maximumDbm: Int,
    val samples: Int,
) {
    val rating: String get() = WifiStrength.rating(averageDbm)
}

object WifiStrength {
    fun rating(dbm: Int): String = when {
        dbm >= -50 -> "Excellent"
        dbm >= -60 -> "Good"
        dbm >= -67 -> "Fair"
        dbm >= -70 -> "Weak"
        else -> "Very weak"
    }

    fun average(values: List<Int>): Int? = values.takeIf { it.isNotEmpty() }?.average()?.roundToInt()

    fun ranked(spots: List<WifiSpot>): List<WifiSpot> =
        spots.sortedWith(compareByDescending<WifiSpot> { it.averageDbm }.thenBy { it.name.lowercase() })
}

/** Reads only the active Wi-Fi link. It never scans or stores nearby network names. */
class WifiSignalMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @SuppressLint("MissingPermission", "DEPRECATION")
    fun read(): WifiReading? {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val network = connectivity.activeNetwork ?: return null
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            capabilities.transportInfo as? WifiInfo ?: return null
        } else {
            val network = connectivity.activeNetwork ?: return null
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            wifi.connectionInfo ?: return null
        }
        val rssi = info.rssi
        if (rssi !in -126..0) return null
        return WifiReading(
            rssiDbm = rssi,
            frequencyMhz = info.frequency.takeIf { it > 0 },
            linkSpeedMbps = info.linkSpeed.takeIf { it > 0 },
            atMillis = System.currentTimeMillis(),
        )
    }
}

/** Saved Wi-Fi measurements stay on this device. */
class WifiSpotStore(context: Context) {
    private val prefs = context.getSharedPreferences("wifi_spots", Context.MODE_PRIVATE)

    fun load(): List<WifiSpot> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            WifiSpot(
                id = item.getLong("id"),
                name = item.getString("name"),
                atMillis = item.getLong("at"),
                averageDbm = item.getInt("avg"),
                minimumDbm = item.getInt("min"),
                maximumDbm = item.getInt("max"),
                samples = item.optInt("samples"),
            )
        }
    }.getOrDefault(emptyList())

    fun save(spots: List<WifiSpot>) {
        val array = JSONArray()
        spots.forEach { spot ->
            array.put(JSONObject().apply {
                put("id", spot.id)
                put("name", spot.name)
                put("at", spot.atMillis)
                put("avg", spot.averageDbm)
                put("min", spot.minimumDbm)
                put("max", spot.maximumDbm)
                put("samples", spot.samples)
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "spots"
    }
}
