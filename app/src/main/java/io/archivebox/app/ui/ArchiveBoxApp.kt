package io.archivebox.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import io.archivebox.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import io.archivebox.app.data.*
import kotlinx.coroutines.launch

internal data class ServerRoute(val name: String, val path: String, val icon: ImageVector = Icons.Outlined.Folder)
internal val collectionRoutes = listOf(
    ServerRoute("Snapshots", "admin/core/snapshot/grid/", Icons.Outlined.CollectionsBookmark),
    ServerRoute("AI Agent", "admin/agent/", Icons.Outlined.AutoAwesome),
    ServerRoute("Crawls", "admin/crawls/crawl/", Icons.Outlined.TravelExplore),
    ServerRoute("Scheduled Crawls", "admin/crawls/crawlschedule/", Icons.Outlined.CalendarMonth),
    ServerRoute("Archive Results", "admin/core/archiveresult/", Icons.Outlined.Description),
    ServerRoute("Tags", "admin/core/tag/", Icons.Outlined.Sell),
)
internal val adminRoutes = listOf(
    ServerRoute("Admin", "admin/", Icons.Outlined.Dashboard),
    ServerRoute("Users", "admin/auth/user/", Icons.Outlined.Group),
    ServerRoute("Personas", "admin/personas/persona/", Icons.Outlined.AccountCircle),
    ServerRoute("API Keys", "admin/api/apitoken/", Icons.Outlined.Key),
    ServerRoute("Webhooks", "admin/api/outboundwebhook/", Icons.Outlined.Webhook),
    ServerRoute("Processes", "admin/machine/process/", Icons.Outlined.Memory),
    ServerRoute("Machines", "admin/machine/machine/", Icons.Outlined.Computer),
    ServerRoute("Network Interfaces", "admin/machine/networkinterface/", Icons.Outlined.Lan),
    ServerRoute("Binaries", "admin/machine/binary/", Icons.Outlined.Terminal),
    ServerRoute("Plugins", "admin/environment/plugins/", Icons.Outlined.Extension),
    ServerRoute("Workers", "admin/environment/workers/", Icons.Outlined.Settings),
    ServerRoute("Logs", "admin/environment/logs/", Icons.Outlined.ReceiptLong),
)

internal class NavigationState : ViewModel() {
    val shareRequest = mutableStateOf<IncomingRequest?>(null)
    val connectionRequest = mutableStateOf<IncomingRequest?>(null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ArchiveBoxApp(repository: ArchiveRepository, incoming: IncomingRequest?, onConsumed: () -> Unit, onFinishShare: () -> Unit) {
    val registry by repository.registry.collectAsStateWithLifecycle()
    val connection = registry.active_server
    val setupDismissed by repository.setupDismissed.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf("Archive") }
    var showGuide by rememberSaveable { mutableStateOf(false) }
    var browserPath by rememberSaveable { mutableStateOf<String?>(null) }
    var browserTitle by rememberSaveable { mutableStateOf("") }
    var searchText by rememberSaveable { mutableStateOf("") }
    val navigation: NavigationState = viewModel()
    var shareRequest by navigation.shareRequest
    var connectionRequest by navigation.connectionRequest
    var preventShareDismiss by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(incoming?.nonce) {
        incoming ?: return@LaunchedEffect
        repository.dismissSetup()
        when (incoming.action) {
            "add" -> { shareRequest = incoming; screen = "Add" }
            "search" -> { searchText = incoming.text; screen = "Search" }
            "connect" -> { connectionRequest = incoming; screen = "Settings" }
            "snapshot" -> {
                val c = connection
                if (c == null) { routeError = "Connect to your server before opening an archived page."; screen = "Settings" }
                else if (incoming.server != null && runCatching { normalizeServer(incoming.server) }.getOrNull() != c.server) {
                    routeError = "This page belongs to a different server. Connect to that server in Settings first."
                } else if (incoming.snapshot?.let(ArchiveApi::validId) == true) {
                    browserPath = "snapshot/${incoming.snapshot}/index.html"; browserTitle = "Archived page"
                } else routeError = "This archived-page link has no valid snapshot ID."
            }
        }
        onConsumed()
    }
    if ((!setupDismissed && connection == null && incoming == null && shareRequest == null && connectionRequest == null) || showGuide) {
        SetupGuide(onConnect = { scope.launch { repository.dismissSetup(); showGuide = false; screen = "Settings" } }, onDismiss = {
            scope.launch { repository.dismissSetup(); showGuide = false }
        })
        return
    }
    val tabs = listOf("Archive" to Icons.Outlined.Inventory2, "Search" to Icons.Outlined.Search, "Add" to Icons.Outlined.AddCircleOutline, "Activity" to Icons.Outlined.Downloading, "Settings" to Icons.Outlined.Settings)
    BackHandler(browserPath != null || screen !in listOf("Archive")) { if (browserPath != null) browserPath = null else screen = "Archive" }
    BoxWithConstraints {
        val wide = maxWidth >= 700.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) NavigationRail(Modifier.fillMaxHeight().testTag("navigation.rail"), header = { BrandMark(Modifier.padding(vertical = 24.dp)) }) {
                tabs.forEach { (name, icon) -> NavigationRailItem(selected = screen == name, onClick = { screen = name; browserPath = null }, icon = { Icon(icon, name) }, label = { Text(name) }, modifier = Modifier.testTag("tab.$name")) }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                topBar = {
                    TopAppBar(title = { Text(browserPath?.let { browserTitle } ?: if (screen == "Archive") "ArchiveBox" else if (screen == "Settings") "Connection Settings" else if (screen == "Search") "Search Archive" else if (screen == "Add") "Add URLs" else screen, fontWeight = FontWeight.SemiBold) },
                        navigationIcon = { if (browserPath != null) IconButton(onClick = { browserPath = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to archive") } else if (!wide) BrandMark(Modifier.padding(start = 16.dp, end = 8.dp)) },
                        actions = { if (screen == "Archive") IconButton(onClick = { showGuide = true }, modifier = Modifier.testTag("setup.reopen")) { Icon(Icons.Outlined.HelpOutline, "Setup guide") } })
                },
                bottomBar = {
                    if (!wide) NavigationBar { tabs.forEach { (name, icon) -> NavigationBarItem(selected = screen == name && browserPath == null, onClick = { screen = name; browserPath = null }, icon = { Icon(icon, name) }, label = { Text(name) }, modifier = Modifier.testTag("tab.$name")) } }
                }
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    val c = connection
                    when {
                        browserPath != null && c != null -> ServerBrowser(repository, c, browserPath!!)
                        screen == "Settings" -> ConnectionSettings(repository, connectionRequest, onRequestConsumed = { connectionRequest = null }, onGuide = { showGuide = true })
                        screen == "Add" -> AddScreen(repository, c, "", onConnect = { screen = "Settings" })
                        screen == "Search" -> SearchScreen(repository, c, searchText, onConnect = { screen = "Settings" }, onOpen = { snapshot -> browserTitle = snapshot.title.ifBlank { "Archived page" }; browserPath = "snapshot/${snapshot.id}/index.html" })
                        screen == "Activity" && c != null -> ServerBrowser(repository, c, "admin/")
                        screen == "Activity" -> ConnectPrompt { screen = "Settings" }
                        else -> HomeScreen(c, onAdd = { screen = "Add" }, onSearch = { screen = "Search" }, onConnect = { screen = "Settings" }, onRoute = { browserTitle = it.name; browserPath = it.path })
                    }
                }
            }
        }
    }
    shareRequest?.let { request ->
        ModalBottomSheet(onDismissRequest = { if (!preventShareDismiss) { shareRequest = null; if (request.externalShare) onFinishShare() } }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !preventShareDismiss })) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.91f).testTag("share.sheet")) {
                Text("Save to ArchiveBox", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                AddScreen(repository, registry.default_servers.firstOrNull(), request.text, onConnect = { shareRequest = null; screen = "Settings" }, onDone = { preventShareDismiss = false; shareRequest = null; if (request.externalShare) onFinishShare() }, requestId = request.nonce, onPreventDismiss = { preventShareDismiss = it })
            }
        }
    }
    routeError?.let { error -> AlertDialog(onDismissRequest = { routeError = null }, title = { Text("Couldn't open link") }, text = { Text(error) }, confirmButton = { TextButton(onClick = { routeError = null }) { Text("OK") } }) }
}

@Composable internal fun BrandMark(modifier: Modifier = Modifier, large: Boolean = false) {
    Surface(modifier.size(if (large) 84.dp else 34.dp), color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(if (large) 24.dp else 10.dp)) {
        Image(painterResource(R.drawable.archivebox_logo), "ArchiveBox", modifier = Modifier.fillMaxSize())
    }
}

@Composable private fun HomeScreen(connection: ServerConfiguration?, onAdd: () -> Unit, onSearch: () -> Unit, onConnect: () -> Unit, onRoute: (ServerRoute) -> Unit) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().testTag("home"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("YOUR WEB, PRESERVED.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("A home for the web\nyou want to keep.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Save a link today. Find it here tomorrow.", style = MaterialTheme.typography.bodyLarge)
                    FilledTonalButton(onClick = onAdd, modifier = Modifier.testTag("home.add")) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Save something good") }
                }
            }
        }
        item { Surface(onClick = onConnect, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) { ListItem(headlineContent = { Text(if (connection == null) "Connect your server" else "Your ArchiveBox server") }, supportingContent = { Text(connection?.server ?: "Your data. Your devices. Your archive.", maxLines = 1, overflow = TextOverflow.Ellipsis) }, leadingContent = { Icon(if (connection == null) Icons.Outlined.Link else Icons.Outlined.Dns, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) }) } }
        item { SectionTitle("Collection") }
        item { RouteRow(ServerRoute("Search Archive", "", Icons.Outlined.Search), true, onSearch) }
        items(collectionRoutes) { route -> RouteRow(route, connection != null) { onRoute(route) } }
        item { SectionTitle("Administration") }
        items(adminRoutes) { route -> RouteRow(route, connection != null) { onRoute(route) } }
        item { SectionTitle("Help & community") }
        items(listOf("ArchiveBox documentation" to "https://github.com/ArchiveBox/ArchiveBox/wiki", "Browser extension" to "https://github.com/ArchiveBox/archivebox-browser-extension", "Community forum" to "https://zulip.archivebox.io", "Report a bug" to "https://github.com/ArchiveBox/android-archivebox/issues", "Source code" to "https://github.com/ArchiveBox/android-archivebox")) { (name, url) ->
            RouteRow(ServerRoute(name, url, Icons.Outlined.OpenInNew), true) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
        item { Text("Your data. Your devices. Your archive.", modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable internal fun SectionTitle(title: String) { Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) }
@Composable private fun RouteRow(route: ServerRoute, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().testTag("route.${route.name}")) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(route.icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Text(route.name, Modifier.weight(1f), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}
@Composable internal fun ConnectPrompt(onConnect: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Dns, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp)); Text("Connect your server", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp)); Text("Your archive lives on your own ArchiveBox server. Add its address and API key to get started.")
        Spacer(Modifier.height(20.dp)); Button(onClick = onConnect, modifier = Modifier.testTag("connect.open")) { Text("Connection Settings") }
    }
}
@Composable internal fun ErrorCard(message: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().testTag("error"), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Outlined.ErrorOutline, null); Text(message, style = MaterialTheme.typography.bodyMedium) }
    }
}
