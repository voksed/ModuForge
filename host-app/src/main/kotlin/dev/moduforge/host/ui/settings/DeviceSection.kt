package dev.moduforge.host.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.moduforge.host.BuildConfig
import dev.moduforge.host.R
import dev.moduforge.host.device.ModuForgeAccessibilityService

/** The system side of the device capabilities: the accessibility service and the camera permission. */
@Composable
internal fun DeviceSection() {
    val context = LocalContext.current
    var screenOn by remember { mutableStateOf(ModuForgeAccessibilityService.instance != null) }
    var cameraOn by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    // The user changes both outside the app, so the state is read again whenever the screen comes back.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                screenOn = ModuForgeAccessibilityService.instance != null
                cameraOn = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraOn = it }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.device_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.device_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!BuildConfig.SCREEN_CONTROL) {
            Text(
                stringResource(R.string.device_screen_absent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(stringResource(if (screenOn) R.string.device_screen_on else R.string.device_screen_off))
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                Text(stringResource(R.string.device_screen_open))
            }
        }
        if (BuildConfig.SCREEN_CONTROL && !screenOn) {
            Text(
                stringResource(R.string.device_screen_restricted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(stringResource(if (cameraOn) R.string.device_camera_on else R.string.device_camera_off))
        if (!cameraOn) {
            OutlinedButton(onClick = { camera.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.device_camera_allow)) }
        }
    }
}
