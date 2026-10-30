package io.github.whereareyou.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whereareyou.R
import io.github.whereareyou.WhereApp
import io.github.whereareyou.service.LocationService
import io.github.whereareyou.ui.theme.WhereTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as WhereApp
    private val pendingInvite = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            WhereTheme { WhereAreYouApp(app, pendingInvite) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.dataString?.let { pendingInvite.value = it }
    }

    override fun onStart() {
        super.onStart()
        app.repo.acquire()
    }

    override fun onStop() {
        app.repo.release()
        super.onStop()
    }
}

private enum class Tab(val label: Int) { MAP(R.string.tab_map), CIRCLES(R.string.tab_circles), SETTINGS(R.string.tab_settings) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhereAreYouApp(app: WhereApp, pendingInvite: MutableState<String?>) {
    val context = LocalContext.current
    val repo = app.repo
    var tab by rememberSaveable { mutableStateOf(Tab.MAP) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val sharing by repo.sharingActive.collectAsStateWithLifecycle()
    var joinDialog by remember { mutableStateOf(false) }

    fun toast(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    val permissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun startSharing() {
        val lm = context.getSystemService(LocationManager::class.java)
        if (!LocationManagerCompat.isLocationEnabled(lm)) toast(context.getString(R.string.location_disabled))
        LocationService.start(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            startSharing()
        } else {
            toast(context.getString(R.string.permission_denied))
        }
    }

    val toggleSharing: () -> Unit = {
        if (sharing) {
            LocationService.stop(context)
        } else {
            val missing = permissions.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) startSharing() else permissionLauncher.launch(missing.toTypedArray())
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(tab.label)) }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.MAP -> Icons.Filled.Place
                                    Tab.CIRCLES -> Icons.Filled.AccountCircle
                                    Tab.SETTINGS -> Icons.Filled.Settings
                                },
                                contentDescription = null
                            )
                        },
                        label = { Text(stringResource(t.label)) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.MAP -> MapScreen(repo, onToggleSharing = toggleSharing, onGoToCircles = { tab = Tab.CIRCLES })
                Tab.CIRCLES -> CirclesScreen(repo, onJoin = { joinDialog = true })
                Tab.SETTINGS -> SettingsScreen(app, onMessage = { toast(it) })
            }
        }
    }

    if (joinDialog || pendingInvite.value != null) {
        JoinDialog(
            initial = pendingInvite.value,
            onDismiss = {
                joinDialog = false
                pendingInvite.value = null
            },
            onJoin = { name, secret, share ->
                joinDialog = false
                pendingInvite.value = null
                if (repo.joinCircle(name, secret, share)) {
                    toast(context.getString(R.string.joined, name))
                    tab = Tab.MAP
                } else {
                    toast(context.getString(R.string.already_member))
                }
            }
        )
    }
}
