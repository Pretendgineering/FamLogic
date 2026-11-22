package io.github.pretendgineering.famlogic

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Settings → Sharing, Google edition only: what Google Play services is used for. */
@Composable
internal fun GoogleCard(prefs: Prefs) {
    val context = LocalContext.current
    val play = remember { Flavor.playServicesAvailable(context) }
    var motionOn by remember { mutableStateOf(prefs.motionDriving) }
    var permitted by remember { mutableStateOf(Motion.permitted(context)) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permitted = ok
        denied = !ok
        LocationService.start(context)
    }

    SettingsCard("Google Play services") {
        if (!play) {
            Hint(
                "Google Play services isn't available on this phone, so FamLogic uses Android's own location " +
                    "and works out driving from GPS speed, the same as the FOSS build.",
            )
            return@SettingsCard
        }
        Hint("Your location comes from Google's fused location provider: quicker fixes, and better indoors.")
        SwitchRow(
            title = "Detect driving with motion sensors",
            detail = when {
                !motionOn -> "Off: driving is worked out from GPS speed."
                !permitted -> "Needs the \"Physical activity\" permission. Until then, driving is worked out from GPS speed."
                else -> "Google's activity recognition tells when you're in a vehicle. Fewer false alarms from trains or fast bikes than GPS speed."
            },
            checked = motionOn,
            onChange = { on ->
                motionOn = on
                prefs.motionDriving = on
                if (on && !permitted) ask.launch(Motion.PERMISSION) else LocationService.start(context)
            },
        )
        if (motionOn && !permitted) {
            TextButton(onClick = {
                if (denied) {
                    // Android won't ask again after two refusals: open the app's settings instead.
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } else {
                    ask.launch(Motion.PERMISSION)
                }
            }) { Text(if (denied) "Open app settings" else "Allow physical activity") }
        }
    }
}
