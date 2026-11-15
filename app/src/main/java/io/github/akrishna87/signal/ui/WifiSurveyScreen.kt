package io.github.akrishna87.signal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.akrishna87.signal.WIFI_SPOT_MILLIS
import io.github.akrishna87.signal.WifiMeasuring
import io.github.akrishna87.signal.WifiSpot
import io.github.akrishna87.signal.WifiStrength
import io.github.akrishna87.signal.WifiSurveyViewModel
import kotlinx.coroutines.delay

@Composable
fun WifiSurveyScreen(vm: WifiSurveyViewModel = viewModel()) {
    val current by vm.current.collectAsStateWithLifecycle()
    val spots by vm.spots.collectAsStateWithLifecycle()
    val measuring by vm.measuring.collectAsStateWithLifecycle()
    val justSaved by vm.justSaved.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var asking by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }

    val busy = measuring != null
    val view = LocalView.current
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(4_000)
            vm.messageShown()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Wifi, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Home Wi-Fi signal", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Walk to a room, measure for 20 seconds, then compare the average Wi-Fi signal. " +
                        "Use the same connected network for every spot.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (current == null) {
                    Text(
                        "Not connected to Wi-Fi. Connect to your home network to start.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Current signal", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${current!!.rssiDbm} dBm · ${WifiStrength.rating(current!!.rssiDbm)}",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    current!!.frequencyMhz?.let { Text("${it} MHz", style = MaterialTheme.typography.bodySmall) }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val active = measuring
                if (active == null) {
                    Button(onClick = { asking = true }, enabled = current != null, modifier = Modifier.fillMaxWidth()) {
                        Text("Measure this spot")
                    }
                } else {
                    WifiMeasuringPanel(active, current?.rssiDbm, onCancel = vm::cancelMeasuring)
                }
            }
        }

        val ranked = WifiStrength.ranked(spots)
        if (ranked.isNotEmpty()) {
            item { Text("Best Wi-Fi spots", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            itemsIndexed(ranked, key = { _, spot -> spot.id }) { index, spot ->
                WifiSpotRow(
                    rank = index + 1,
                    spot = spot,
                    fresh = spot.id == justSaved,
                    onDelete = { deleting = spot.id },
                )
            }
        } else if (measuring == null) {
            item {
                Text(
                    "No Wi-Fi spots measured yet. Start with the room farthest from your router.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (asking) WifiSpotNameDialog(
        onStart = { vm.startMeasuring(it); asking = false },
        onDismiss = { asking = false },
    )
    deleting?.let { id ->
        val name = spots.firstOrNull { it.id == id }?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete $name?") },
            confirmButton = { TextButton(onClick = { vm.deleteSpot(id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WifiMeasuringPanel(m: WifiMeasuring, rssi: Int?, onCancel: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(m.startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Measuring ${m.name}… ${m.secondsLeft(now)} s", style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(progress = { m.progress(now) }, modifier = Modifier.fillMaxWidth())
        Text(
            rssi?.let { "$it dBm · ${WifiStrength.rating(it)}" } ?: "Waiting for a Wi-Fi reading…",
            fontWeight = FontWeight.Medium,
        )
        Text("Keep the phone in your hand as you normally use it.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

@Composable
private fun WifiSpotRow(rank: Int, spot: WifiSpot, fresh: Boolean, onDelete: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (fresh) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("#$rank  ${spot.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${spot.averageDbm} dBm average · ${spot.rating}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${spot.minimumDbm} to ${spot.maximumDbm} dBm · ${spot.samples} readings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = "Delete ${spot.name}") }
        }
    }
}

@Composable
private fun WifiSpotNameDialog(onStart: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this spot") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("For example: bedroom, kitchen or beside the router.")
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Room or spot") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onStart(name) }) { Text("Start 20-second test") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
