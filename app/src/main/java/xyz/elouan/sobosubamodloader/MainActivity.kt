package xyz.elouan.sobosubamodloader

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.elouan.sobosubamodloader.ui.theme.SobosubaModLoaderTheme
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SobosubaModLoaderApp() }
    }
}

private enum class Destination(val label: String) {
    Home("Home"),
    Mods("Mods"),
    Settings("Settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun SobosubaModLoaderApp() {
    val application = LocalContext.current.applicationContext as Application
    val model: LoaderViewModel = viewModel(factory = LoaderViewModel.factory(application))
    var destination by remember { mutableStateOf(Destination.Home) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.installMod(uri) { message -> scope.launch { snackbar.showSnackbar(message) } }
    }

    DisposableEffect(Unit) {
        val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == LoaderRepository.SHIZUKU_PERMISSION_REQUEST_CODE) {
                model.refresh()
                scope.launch {
                    snackbar.showSnackbar(
                        if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            "Shizuku permission granted"
                        } else {
                            "Shizuku permission denied"
                        }
                    )
                }
            }
        }
        Shizuku.addRequestPermissionResultListener(permissionListener)
        onDispose { Shizuku.removeRequestPermissionResultListener(permissionListener) }
    }

    LaunchedEffect(Unit) { model.refresh() }

    SobosubaModLoaderTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Sobosuba Mod Loader") }) },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    Destination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = {
                                Icon(
                                    when (item) {
                                        Destination.Home -> Icons.Default.Home
                                        Destination.Mods -> Icons.Default.SportsEsports
                                        Destination.Settings -> Icons.Default.Settings
                                    },
                                    contentDescription = item.label
                                )
                            },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        ) { padding ->
            when (destination) {
                Destination.Home -> HomeScreen(model, padding, snackbar)
                Destination.Mods -> ModsScreen(model, padding, zipPicker)
                Destination.Settings -> SettingsScreen(model, padding)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun HomeScreen(model: LoaderViewModel, padding: PaddingValues, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Setup", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (model.shizukuConnected) "Shizuku connected" else "Shizuku is not connected",
                color = if (model.shizukuConnected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        model.gameStatus,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (model.gameVersion != null) Text("Version ${model.gameVersion}")
                    if (!model.shizukuConnected) {
                        Button(onClick = {
                            model.requestShizukuPermission { message ->
                                scope.launch { snackbar.showSnackbar(message) }
                            }
                        }) {
                            Text("Connect to Shizuku")
                        }
                    } else {
                        Button(onClick = { model.patchGame { message -> scope.launch { snackbar.showSnackbar(message) } } }) {
                            Text(if (model.isDebuggable) "Game is patched" else "Patch game")
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { model.launchGame { message -> scope.launch { snackbar.showSnackbar(message) } } },
                    enabled = model.gamePackage != null
                ) { Text("Launch game") }
                OutlinedButton(
                    onClick = { model.refresh() },
                    enabled = !model.loading
                ) { Text("Refresh") }
            }
        }
        if (model.loading) item { CircularProgressIndicator(Modifier.size(28.dp)) }
    }
}

@androidx.compose.runtime.Composable
private fun ModsScreen(
    model: LoaderViewModel,
    padding: PaddingValues,
    zipPicker: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, Uri?>
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Installed mods", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                Button(onClick = { zipPicker.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                    Icon(Icons.Default.Download, null)
                    Spacer(Modifier.size(6.dp))
                    Text("Install ZIP")
                }
            }
        }
        if (model.mods.isEmpty()) {
            item { Text("No mod-package.json files found. Install a mod ZIP to get started.") }
        } else {
            items(model.mods, key = { it.id }) { mod ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(mod.name, fontWeight = FontWeight.Bold)
                            Text("${mod.version} by ${mod.author}")
                            if (mod.description.isNotBlank()) Text(mod.description)
                        }
                        IconButton(onClick = { model.removeMod(mod) }) {
                            Icon(Icons.Default.Delete, "Remove ${mod.name}")
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SettingsScreen(model: LoaderViewModel, padding: PaddingValues) {
    var customPackageText by remember(model.customPackage) { mutableStateOf(model.customPackage) }

    Column(
        Modifier.fillMaxSize().padding(padding).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text("Shizuku: ${if (model.shizukuConnected) "Connected" else "Disconnected"}")
        Text("Detected package: ${model.gamePackage ?: "Not installed"}")
        Text("Patch status: ${if (model.isDebuggable) "Debuggable" else "Not patched"}")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Install separate modded copy", fontWeight = FontWeight.Bold)
                Text("Use ${LoaderRepository.DEBUG_PACKAGE} instead of replacing Sobosuba.")
            }
            Switch(
                checked = model.separateInstall,
                onCheckedChange = model::updateSeparateInstall
            )
        }
        Text("Custom package name", fontWeight = FontWeight.Bold)
        Text("Enter a package name if your patched game uses a different one (e.g. com.keepeetron.sobosuba.morphe).")
        OutlinedTextField(
            value = customPackageText,
            onValueChange = { customPackageText = it },
            label = { Text("Package name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(onClick = {
            model.updateCustomPackage(customPackageText)
        }) {
            Text("Save package name")
        }

        Text(
            "This app patches your installed APK and copies mod files into the game's private user data using Shizuku."
        )
    }
}

class LoaderViewModel(application: Application) : ViewModel() {
    private val repository = LoaderRepository(application)

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(LoaderViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return LoaderViewModel(application) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }

    var shizukuConnected by mutableStateOf(false)
        private set
    var gamePackage by mutableStateOf<String?>(null)
        private set
    var gameVersion by mutableStateOf<String?>(null)
        private set
    var isDebuggable by mutableStateOf(false)
        private set
    var gameStatus by mutableStateOf("Sobosuba not detected")
        private set
    var mods by mutableStateOf(emptyList<InstalledMod>())
        private set
    var loading by mutableStateOf(false)
        private set
    var separateInstall by mutableStateOf(repository.separateInstallEnabled())
        private set
    var customPackage by mutableStateOf(repository.customPackage())
        private set

    fun updateCustomPackage(value: String) {
        repository.setCustomPackage(value)
        customPackage = value
        refresh()
    }

    fun refresh() {
        loading = true
        shizukuConnected = repository.isShizukuReady()
        val game = repository.detectGame()
        gamePackage = game?.packageName
        gameVersion = game?.versionName
        gameStatus = if (game == null) "Sobosuba not detected" else "Sobosuba detected"
        isDebuggable = game?.let { repository.isDebuggable(it.packageName) } == true
        mods = if (game != null && isDebuggable) repository.listMods(game.packageName) else emptyList()
        loading = false
    }

    fun requestShizukuPermission(callback: (String) -> Unit) {
        callback(repository.requestShizukuPermission())
        refresh()
    }

    fun updateSeparateInstall(enabled: Boolean) {
        repository.setSeparateInstallEnabled(enabled)
        separateInstall = enabled
        refresh()
    }

    fun patchGame(callback: (String) -> Unit) {
        val packageName = gamePackage ?: return callback("Sobosuba is not installed")
        loading = true
        repository.patchGame(packageName) { message ->
            loading = false
            callback(message)
            refresh()
        }
    }

    fun installMod(uri: Uri, callback: (String) -> Unit) {
        val packageName = gamePackage ?: return callback("Sobosuba is not installed")
        if (!isDebuggable) return callback("Patch Sobosuba before installing mods")
        loading = true
        repository.installMod(packageName, uri) { message ->
            loading = false
            callback(message)
            refresh()
        }
    }

    fun removeMod(mod: InstalledMod) {
        gamePackage?.let {
            repository.removeMod(it, mod)
            refresh()
        }
    }

    fun launchGame(callback: (String) -> Unit) {
        val packageName = gamePackage ?: return callback("Sobosuba is not installed")
        val intent = repository.launchIntent(packageName)
        if (intent == null) callback("Unable to launch Sobosuba")
        else repository.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
