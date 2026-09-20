package io.archivebox.app.ui

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable internal fun SetupGuide(onConnect: () -> Unit, onDismiss: () -> Unit) {
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(route != null) { route = null }
    route?.let { selected ->
        SetupDetail(selected, onBack = { route = null }, onConnect = onConnect)
        return
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { BrandMark(large = true); TextButton(onClick = onDismiss, modifier = Modifier.testTag("setup.dismiss")) { Text("Maybe later") } }
        Text("YOUR WEB, PRESERVED.", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text("Keep the pages.\nKeep the possibilities.", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("A personal library for the internet. Save articles, research, and little discoveries before they disappear.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Outlined.Share, null); Column { Text("Share it", fontWeight = FontWeight.SemiBold); Text("Send a link from any Android app.", style = MaterialTheme.typography.bodyMedium) } }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Outlined.Dns, null); Column { Text("Your server saves it", fontWeight = FontWeight.SemiBold); Text("ArchiveBox captures pages in multiple formats.", style = MaterialTheme.typography.bodyMedium) } }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Outlined.CollectionsBookmark, null); Column { Text("Find it again", fontWeight = FontWeight.SemiBold); Text("Browse, search, and organize your archive.", style = MaterialTheme.typography.bodyMedium) } }
            }
        }
        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("setup.connect")) { Text("I already have a server") }
        Text("Choose a home for your archive", style = MaterialTheme.typography.titleLarge)
        Text("This app connects to ArchiveBox. Run the server on your own computer, NAS, or a machine in the cloud.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(
            Triple("Run it on your Mac", "Get ArchiveBox Server.app for Apple Silicon.", "mac"),
            Triple("Docker or a home server", "A great fit for your NAS or a spare computer.", "docker"),
            Triple("Install with Python", "Use the ArchiveBox CLI on your own machine.", "python"),
            Triple("Hosting options", "Learn about running ArchiveBox on a VPS.", "hosting"),
        ).forEach { (title, detail, key) ->
            OutlinedCard(onClick = { route = key }, modifier = Modifier.fillMaxWidth().testTag("setup.$key")) {
                ListItem(headlineContent = { Text(title) }, supportingContent = { Text(detail) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) })
            }
        }
        Text("No tracking. No developer cloud account. Your archive stays on the server you choose.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SetupDetail(route: String, onBack: () -> Unit, onConnect: () -> Unit) {
    val context = LocalContext.current
    val title = when (route) { "mac" -> "A home on your Mac"; "docker" -> "Run it with Docker"; "python" -> "Install with Python"; else -> "Host your own archive" }
    val docs = when (route) {
        "mac" -> "https://github.com/ArchiveBox/ios-archivebox/releases"
        "docker" -> "https://github.com/ArchiveBox/ArchiveBox#quickstart"
        "python" -> "https://github.com/ArchiveBox/ArchiveBox/wiki/Install"
        else -> "https://github.com/ArchiveBox/ArchiveBox/wiki/Hosting"
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("guide.$route"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("guide.back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("Setup guide") }
            BrandMark()
        }
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Set this up on your computer or server. Your Android phone connects to it to save and browse pages.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (route) {
            "docker" -> {
                GuideStep("1", "Start the server", "Install Docker on your computer or NAS, then run this command in its terminal. The named volume keeps your collection between container restarts.")
                CommandCard("Docker command", "docker run -d --name archivebox \\\n  --restart unless-stopped \\\n  -v archivebox-data:/data \\\n  -p 5797:5797 \\\n  archivebox/archivebox:dev")
                GuideStep("2", "Finish web setup", "On that computer, open http://localhost:5797/admin/. Create the first administrator and complete the setup wizard. Set the server address to a hostname or IP your phone can reach.")
                GuideStep("3", "Connect your phone", "Return here with the computer's LAN or tailnet address, such as http://192.168.1.20:5797. Use Get an API key in Connection Settings, then test and save.")
            }
            "python" -> {
                GuideStep("1", "Install ArchiveBox", "Install uv on your computer, then run these commands in its terminal. The installer selects Python 3.13 and the ArchiveBox 0.9 release series.")
                CommandCard("Install command", "uv tool install --python 3.13 \\\n  --prerelease explicit --upgrade \\\n  'archivebox>=0.9.0rc0,<0.10'")
                GuideStep("2", "Create a collection and start it", "Choose a location with space for your archive. Run the server from this collection folder; keep its terminal open while you use the app.")
                CommandCard("Start commands", "mkdir archivebox-data\ncd archivebox-data\narchivebox init\narchivebox server 0.0.0.0:5797")
                GuideStep("3", "Finish setup and connect", "Open http://localhost:5797/admin/ on the server computer to create your administrator and finish setup. On your phone, connect using the computer's LAN or tailnet address and an API key.")
            }
            "mac" -> {
                GuideStep("1", "Get ArchiveBox Server.app", "Download the official companion app from GitHub Releases on an Apple Silicon Mac, then move it to Applications and open it.")
                GuideStep("2", "Set up your archive", "Choose where your archive lives, create your first administrator, and start the server. The companion can keep archiving while you use other apps.")
                GuideStep("3", "Connect from Android", "Use the address shown in the Mac server's Network access settings. Keep the Mac awake and reachable over Wi-Fi or your VPN. Enter that address and its API key in this app.")
            }
            else -> {
                GuideStep("1", "Choose a server you control", "ArchiveBox can run on a VPS or another hosted Linux machine. Choose enough persistent disk space for the pages and media you want to keep.")
                GuideStep("2", "Deploy ArchiveBox", "Follow the official hosting guide and use its Docker or Python installation route. Keep your collection on persistent storage and configure a domain with HTTPS or private VPN access.")
                GuideStep("3", "Connect your app", "Complete the server's web setup, create an administrator API key, and add the reachable HTTPS or tailnet address to Connection Settings. Hosting is provided by the server provider you choose.")
            }
        }
        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(docs))) }, modifier = Modifier.fillMaxWidth().testTag("guide.docs")) {
            Icon(Icons.Outlined.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text(if (route == "mac") "Download the Mac server app" else "Open the full setup guide")
        }
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp)) {
            Text("On your phone, localhost means this phone. Use your server computer's network address instead. Connection Settings can discover reachable servers on port 5797.", Modifier.padding(18.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("setup.connect")) { Text("I already have a server") }
    }
}

@Composable private fun GuideStep(number: String, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) { Text(number, Modifier.padding(horizontal = 13.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun CommandCard(label: String, command: String) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectionContainer { Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("guide.command")) }
            TextButton(onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, command)); copied = true }, modifier = Modifier.testTag("guide.copy.$label")) { Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (copied) "Copied" else "Copy commands") }
        }
    }
}
