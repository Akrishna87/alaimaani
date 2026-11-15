package io.github.akrishna87.signal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlin.coroutines.cancellation.CancellationException

data class WifiMeasuring(
    val name: String,
    val startedAt: Long,
    val readings: List<Int>,
) {
    fun progress(now: Long) = ((now - startedAt).toFloat() / WIFI_SPOT_MILLIS).coerceIn(0f, 1f)
    fun secondsLeft(now: Long) = ((WIFI_SPOT_MILLIS - (now - startedAt) + 999) / 1000).coerceAtLeast(0)
}

const val WIFI_SPOT_MILLIS = 20_000L

class WifiSurveyViewModel(app: Application) : AndroidViewModel(app) {
    private val monitor = WifiSignalMonitor(app)
    private val store = WifiSpotStore(app)

    private val _spots = MutableStateFlow(store.load())
    val spots: StateFlow<List<WifiSpot>> = _spots.asStateFlow()

    private val _measuring = MutableStateFlow<WifiMeasuring?>(null)
    val measuring: StateFlow<WifiMeasuring?> = _measuring.asStateFlow()

    private val _justSaved = MutableStateFlow<Long?>(null)
    val justSaved: StateFlow<Long?> = _justSaved.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val current: StateFlow<WifiReading?> = flow {
        while (true) {
            val reading = try {
                monitor.read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                null
            }
            emit(reading)
            delay(1_000)
        }
    }.onEach(::recordMeasurement).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun startMeasuring(name: String) {
        _message.value = null
        _justSaved.value = null
        _measuring.value = WifiMeasuring(name.trim().ifEmpty { "Spot" }, System.currentTimeMillis(), emptyList())
    }

    fun cancelMeasuring() {
        _measuring.value = null
    }

    fun deleteSpot(id: Long) {
        _spots.value = _spots.value.filterNot { it.id == id }
        store.save(_spots.value)
    }

    fun messageShown() {
        _message.value = null
    }

    private fun recordMeasurement(reading: WifiReading?) {
        val active = _measuring.value ?: return
        val now = System.currentTimeMillis()
        val samples = if (reading == null) active.readings else active.readings + reading.rssiDbm
        if (now - active.startedAt < WIFI_SPOT_MILLIS) {
            _measuring.value = active.copy(readings = samples)
            return
        }
        _measuring.value = null
        val average = WifiStrength.average(samples)
        if (average == null) {
            _message.value = "No Wi-Fi reading was available at ${active.name}. Stay connected and try again."
            return
        }
        val spot = WifiSpot(
            id = now,
            name = active.name,
            atMillis = now,
            averageDbm = average,
            minimumDbm = samples.minOrNull() ?: average,
            maximumDbm = samples.maxOrNull() ?: average,
            samples = samples.size,
        )
        _spots.value = _spots.value + spot
        store.save(_spots.value)
        _justSaved.value = spot.id
    }
}
