package io.archivebox.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.archivebox.app.data.*
import io.archivebox.app.BuildConfig
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException

@Composable internal fun ConnectionSettings(repository: ArchiveRepository, incoming: IncomingRequest?, onRequestConsumed: () -> Unit, onGuide: () -> Unit, discoverOnOpen: Boolean = false, onDiscoveryStarted: () -> Unit = {}) {
    val registry by repository.registry.collectAsStateWithLifecycle()
    val connection = registry.active_server
    var server by rememberSaveable { mutableStateOf(connection?.server.orEmpty()) }
    // Credentials intentionally remain in memory, never in Android's saved-instance bundle.
    var token by remember { mutableStateOf(connection?.takeIf { sameOrigin(it.server, server) }?.token.orEmpty()) }
    var showToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<DiscoveredServer>>(emptyList()) }
    var hints by rememberSaveable { mutableStateOf("") }
    var disconnect by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf<ServerConfiguration?>(null) }
    var completed by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.importantForAutofill
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        onDispose { view.importantForAutofill = previous }
    }
    fun editServer(value: String) {
        val preservesOrigin = runCatching { sameOrigin(normalizeServer(server), normalizeServer(value)) }.getOrDefault(false)
        if (!preservesOrigin) { token = ""; showToken = false }
        server = value
        status = null
    }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterPermission?.invoke() else error = "Allow local network access to reach ArchiveBox servers on your LAN or tailnet."
        afterPermission = null
    }
    fun withNetworkPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 37 && context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
            afterPermission = action; permissionLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK")
        } else action()
    }
    LaunchedEffect(incoming?.nonce) {
        if (incoming != null) {
            server = incoming.server.orEmpty(); token = incoming.token.orEmpty()
            status = "Review this connection request, then test and save it. Your saved connection hasn't changed."
            onRequestConsumed()
        }
    }
    fun check(save: Boolean) {
        // Bind the user-approved address and key before a permission prompt or network suspension.
        val requestedServer = server
        val requestedToken = token.trim()
        withNetworkPermission {
            scope.launch {
                busy = true; error = null; status = null
                try {
                    val canonical = repository.api.discover(requestedServer)
                    val previous = registry.servers.find { it.server == canonical }
                    val candidate = if (previous == null) ServerConfiguration(name = java.net.URI(canonical).host ?: canonical, server = canonical, token = requestedToken)
                        else previous.copy(token = requestedToken)
                    repository.api.testToken(candidate)
                    server = canonical
                    if (save) { repository.saveConnection(candidate); repository.dismissSetup() }
                    status = if (save) "Connected. Your server and API key are saved on this device." else "Connection verified. Your server accepted this API key."
                } catch (e: Exception) { error = e.message ?: "Couldn't connect to the server." }
                finally { busy = false }
            }
        }
    }
    fun scan() {
        if (scanning) return
        withNetworkPermission {
            val previousScan = scanJob
            scanJob = scope.launch {
                previousScan?.cancelAndJoin()
                scanning = true; found = emptyList(); error = null; status = null; completed = 0; total = 0
                try {
                    Discovery(context, repository.api).scan(listOf(hints) + registry.servers.map { it.server }, onProgress = { done, count -> completed = done; total = count }, onSweepFinished = {
                        scanning = false
                        if (found.isEmpty()) status = "No ArchiveBox servers found. Enter an address above, or add a tailnet hostname and scan again."
                    }) { candidate ->
                        found = (found.filterNot { it.url == candidate.url } + candidate).sortedBy { it.url }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) { error = e.message ?: "Discovery failed." }
                finally { scanning = false }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (discoverOnOpen) {
            scan()
            onDiscoveryStarted()
        } else if (Build.VERSION.SDK_INT < 37 || context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED) {
            // Ordinary settings entry only starts discovery when permission is already available.
            scan()
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Dns, null, Modifier.size(34.dp)); Text("Your archive, anywhere", style = MaterialTheme.typography.headlineSmall)
                Text("Connect over your home network, Tailscale, or the internet. Use the address you open in a browser.")
            }
        }
        if (registry.servers.isNotEmpty()) {
            SectionTitle("Remembered connections")
            registry.servers.forEach { saved ->
                OutlinedCard(onClick = {
                    scope.launch {
                        busy = true; error = null
                        try {
                            repository.selectConnection(saved.id)
                            server = saved.server; token = saved.token; showToken = false
                            status = "Checking ${saved.name}…"
                            repository.api.discover(saved.server)
                            repository.api.testToken(saved)
                            status = "Connected to ${saved.name}. Your saved API key is ready."
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (e: Exception) { status = null; error = e.message ?: "Couldn't switch servers." }
                        finally { busy = false }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("history.server.${saved.id}")) {
                    ListItem(headlineContent = { Text(saved.name) }, supportingContent = { Text(saved.server) },
                        leadingContent = { Icon(Icons.Outlined.History, null) }, trailingContent = {
                            Row {
                                if (saved.token.isNotBlank()) Icon(Icons.Outlined.CheckCircle, "Saved API key; ready to switch")
                                IconButton(onClick = { forget = saved }, enabled = !busy, modifier = Modifier.testTag("history.forget.${saved.id}")) {
                                    Icon(Icons.Outlined.RemoveCircleOutline, "Forget server")
                                }
                            }
                        })
                }
            }
        }
        OutlinedTextField(server, { editServer(it) }, enabled = !busy, label = { Text("Server URL") }, placeholder = { Text("http://archivebox.local:5797") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, modifier = Modifier.fillMaxWidth().testTag("connection.url"), leadingIcon = { Icon(Icons.Outlined.Link, null) })
        OutlinedTextField(token, { token = it; status = null }, enabled = !busy, label = { Text("API key") }, singleLine = true, visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().testTag("connection.token"), leadingIcon = { Icon(Icons.Outlined.Key, null) }, trailingIcon = { IconButton(onClick = { showToken = !showToken }) { Icon(if (showToken) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showToken) "Hide API key" else "Show API key") } })
        TextButton(onClick = {
            runCatching { normalizeServer(server) }.onSuccess { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${it.trimEnd('/')}/admin/api/apitoken/"))) }.onFailure { error = it.message }
        }, enabled = server.isNotBlank()) { Icon(Icons.Outlined.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Get an API key from your server") }
        error?.let { ErrorCard(it) }
        status?.let { Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.testTag("connection.status")) { Text(it, Modifier.fillMaxWidth().padding(16.dp)) } }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { check(false) }, enabled = !busy && server.isNotBlank() && token.isNotBlank(), modifier = Modifier.weight(1f).testTag("connection.test")) { Text("Test connection") }
            Button(onClick = { check(true) }, enabled = !busy && server.isNotBlank() && token.isNotBlank(), modifier = Modifier.weight(1f).testTag("connection.save")) { Text("Test & save") }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionTitle("Available servers")
        Text("Choose a server, or enter its address above. Nearby servers on port 5797 appear automatically below.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(hints, { hints = it }, label = { Text("Server hostnames or IP addresses") }, placeholder = { Text("archivebox.tailnet-name.ts.net") }, supportingText = { Text("Optional · paste hostnames, IP addresses, or tailscale status --json") }, maxLines = 4, modifier = Modifier.fillMaxWidth().testTag("connection.hints"))
        OutlinedButton(onClick = { scan() }, enabled = !scanning, modifier = Modifier.fillMaxWidth().testTag("connection.discover")) {
            Icon(Icons.Outlined.Radar, null); Spacer(Modifier.width(8.dp)); Text(if (scanning) "Looking for servers…" else "Discover servers")
        }
        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (scanning) Text("Checked $completed of $total addresses", style = MaterialTheme.typography.bodySmall)
        Text("Checks Bonjour and up to 512 nearby IPv4 addresses. Android cannot read Tailscale’s device list; paste device names or status JSON to check servers elsewhere. Custom ports may need an address entered above.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        found.filterNot { candidate -> registry.servers.any { it.server == candidate.url } }.forEach { candidate ->
            OutlinedCard(onClick = { editServer(candidate.url); status = "Selected ${candidate.label}. Enter its API key, then test and save." }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("discovery.server")) {
                ListItem(headlineContent = { Text(candidate.label) }, supportingContent = { Text(candidate.url) }, leadingContent = { Icon(Icons.Outlined.Dns, null) }, trailingContent = { Icon(Icons.Outlined.AddLink, null) })
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        TextButton(onClick = onGuide, modifier = Modifier.testTag("setup.reopen")) { Icon(Icons.Outlined.HelpOutline, null); Spacer(Modifier.width(8.dp)); Text("Open setup guide") }
        if (connection != null) TextButton(onClick = { disconnect = true }, modifier = Modifier.testTag("connection.disconnect")) { Text("Disconnect from server", color = MaterialTheme.colorScheme.error) }
        Text("Your API key is encrypted on this device. Shared URLs go only to the server you choose. No tracking or analytics.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("ArchiveBox ${BuildConfig.VERSION_NAME} · GPL-3.0-only", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("settings.version"))
    }
    forget?.let { saved ->
        AlertDialog(onDismissRequest = { forget = null }, title = { Text("Forget this server?") },
            text = { Text("Remove ${saved.server} and its saved API key from this device. Your archive and account stay on the server.") },
            confirmButton = { TextButton(onClick = { scope.launch {
                try {
                    repository.removeConnection(saved.id)
                    if (server == saved.server) { server = ""; token = ""; showToken = false; status = null }
                    forget = null
                } catch (e: Exception) { error = e.message ?: "Couldn't forget this server."; forget = null }
            } }) { Text("Forget server") } },
            dismissButton = { TextButton(onClick = { forget = null }) { Text("Cancel") } })
    }
    if (disconnect) AlertDialog(onDismissRequest = { disconnect = false }, title = { Text("Disconnect this device?") }, text = { Text("Your archive stays on the server. The saved API key and browser session will be removed from this device.") }, confirmButton = { TextButton(onClick = { scope.launch { repository.clearConnection(); token = ""; status = null; disconnect = false } }) { Text("Disconnect") } }, dismissButton = { TextButton(onClick = { disconnect = false }) { Text("Cancel") } })
}
