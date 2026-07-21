package com.appblock

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblock.data.AppInfo
import com.appblock.data.BlockedAppsRepository
import com.appblock.data.InstalledAppsProvider
import com.appblock.ui.theme.AppBlockTheme
import com.appblock.util.accessibilitySettingsIntent
import com.appblock.util.isAccessibilityServiceEnabled
import com.appblock.util.overlayPermissionIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    // Re-checked in onResume so the setup card updates when the user returns from Settings.
    private val accessibilityEnabled = mutableStateOf(false)
    private val overlayGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppBlockTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HomeScreen(
                        accessibilityEnabled = accessibilityEnabled.value,
                        overlayGranted = overlayGranted.value,
                        onOpenAccessibility = { startActivity(accessibilitySettingsIntent()) },
                        onOpenOverlay = { startActivity(overlayPermissionIntent(this)) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accessibilityEnabled.value = isAccessibilityServiceEnabled(this)
        overlayGranted.value = Settings.canDrawOverlays(this)
    }
}

@Composable
private fun HomeScreen(
    accessibilityEnabled: Boolean,
    overlayGranted: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { BlockedAppsRepository(context) }

    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var blocked by remember { mutableStateOf(repository.getBlockedPackages()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { InstalledAppsProvider.loadLaunchableApps(context) }
        loading = false
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        item {
            Text(
                text = "App-Block",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "MVP — pick the apps to block",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            SetupCard(
                accessibilityEnabled = accessibilityEnabled,
                overlayGranted = overlayGranted,
                onOpenAccessibility = onOpenAccessibility,
                onOpenOverlay = onOpenOverlay,
            )
        }

        item {
            Text(
                text = "Blocked apps (${blocked.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
        }

        if (loading) {
            item {
                Text(
                    text = "Loading installed apps…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(apps, key = { it.packageName }) { app ->
            AppRow(
                app = app,
                checked = blocked.contains(app.packageName),
                onCheckedChange = { isChecked ->
                    repository.setBlocked(app.packageName, isChecked)
                    blocked = repository.getBlockedPackages()
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun SetupCard(
    accessibilityEnabled: Boolean,
    overlayGranted: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Setup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Both are special permissions — you grant them once in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            PermissionRow(
                label = "Accessibility service",
                granted = accessibilityEnabled,
                actionLabel = "Enable",
                onAction = onOpenAccessibility,
            )
            PermissionRow(
                label = "Display over other apps",
                granted = overlayGranted,
                actionLabel = "Grant",
                onAction = onOpenOverlay,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (granted) "On" else "Not granted",
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        if (!granted) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun AppRow(
    app: AppInfo,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}
