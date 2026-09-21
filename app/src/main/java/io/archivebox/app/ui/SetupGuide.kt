package io.archivebox.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The first-run guide intentionally stays self contained. It explains where the archive lives,
 * then hands connection and discovery to Connection Settings through the existing callback.
 */
@Composable
internal fun SetupGuide(onConnect: () -> Unit, onDismiss: () -> Unit, onDiscover: () -> Unit = onConnect) {
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    val guideScrollState = remember(route) { androidx.compose.foundation.ScrollState(0) }
    BackHandler(route != null) {
        route = if (route == "choices") null else "choices"
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            Modifier.weight(1f).widthIn(max = 620.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                .verticalScroll(guideScrollState)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (route == null) {
                SetupWelcome(onDismiss = onDismiss, onDiscover = onDiscover)
            } else if (route == "choices") {
                SetupChoices(onBack = { route = null }, onConnect = onConnect) { route = it }
            } else {
                SetupDetail(route!!, onBack = { route = "choices" }, onConnect = onConnect)
            }
        }

        Surface(tonalElevation = 3.dp) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (route) {
                    null -> {
                        OutlinedButton(
                            onClick = onDiscover,
                            modifier = Modifier.fillMaxWidth().testTag("setup.discover"),
                        ) {
                            Icon(Icons.Outlined.Wifi, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Search for nearby servers")
                        }
                        OutlinedButton(
                            onClick = onConnect,
                            modifier = Modifier.fillMaxWidth().testTag("setup.skip"),
                        ) { Text("Connect to existing server") }
                        Button(
                            onClick = { route = "choices" },
                            modifier = Modifier.fillMaxWidth().testTag("setup.choose"),
                        ) { Text("Set up a new server") }
                    }
                    "choices" -> {
                        OutlinedButton(
                            onClick = onConnect,
                            modifier = Modifier.fillMaxWidth().testTag("setup.skip"),
                        ) { Text("Connect to existing server") }
                    }
                    else -> {
                        Button(
                            onClick = onConnect,
                            modifier = Modifier.fillMaxWidth().testTag("setup.connect"),
                        ) { Text("My server is ready — connect") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupWelcome(onDismiss: () -> Unit, onDiscover: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        BrandMark(large = true)
        TextButton(onClick = onDismiss, modifier = Modifier.testTag("setup.dismiss")) {
            Text("Maybe later")
        }
    }
    Text(
        "Keep the web that matters to you.",
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.testTag("setup.welcome.heading"),
    )
    Text(
        "ArchiveBox saves copies of web pages so you can revisit them after the originals change or disappear.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Your archive needs a home. A server is the computer that saves and stores your pages. It can be your computer, another machine you manage, or a paid hosting service.",
        style = MaterialTheme.typography.bodyLarge,
    )
    ArchiveDiagram()
    Text(
        "This app connects to your server to save links and browse your archive from your phone. You choose where the files live and who can access them.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SetupChoices(onBack: () -> Unit, onConnect: () -> Unit, onChoice: (String) -> Unit) {
    SetupHeader(onBack)
    SetupHeading(
        "Choose a home for your archive",
        "ArchiveBox is free and open source. You can use a computer you own or pay a company to host it.",
    )
    SetupChoice(
        "Use a Mac",
        "Keep the files on a Mac you own. Connecting this phone also needs network setup.",
        Icons.Outlined.DesktopWindows,
        "mac",
        onChoice,
    )
    SetupChoice(
        "Use a hosting service",
        "Keep your archive online without leaving a computer on at home. Paid separately.",
        Icons.Outlined.Cloud,
        "hosting",
        onChoice,
    )
    Text("Install it yourself", style = MaterialTheme.typography.titleMedium)
    SetupChoice(
        "Docker Compose",
        "For a computer, NAS, or cloud server you manage. Includes archiving tools.",
        Icons.Outlined.Inventory2,
        "docker",
        onChoice,
    )
    SetupChoice(
        "Python / uv / pip",
        "For terminal users who want to manage the installation and dependencies.",
        Icons.Outlined.Terminal,
        "python",
        onChoice,
    )
    Text(
        "Someone setting this up for you? Ask them for your ArchiveBox server address and an API key, then choose “Connect to existing server”.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SetupDetail(route: String, onBack: () -> Unit, onConnect: () -> Unit) {
    val context = LocalContext.current
    val docs = when (route) {
        "mac" -> "https://app.archivebox.io/#your-server-your-choice"
        else -> "https://github.com/ArchiveBox/ArchiveBox#quickstart"
    }

    Column(Modifier.fillMaxWidth().testTag("guide.$route"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SetupHeader(onBack)
        when (route) {
            "mac" -> MacSetup(onConnect)
            "hosting" -> HostingSetup()
            "docker" -> DockerSetup()
            "python" -> PythonSetup()
        }
        OutlinedButton(
            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(docs))) },
            modifier = Modifier.fillMaxWidth().testTag("guide.docs"),
        ) {
            Icon(Icons.Outlined.OpenInNew, null)
            Spacer(Modifier.width(8.dp))
            Text(if (route == "mac") "Mac server setup guide" else "Full README: Docker, NAS, and install options")
        }
    }
}

@Composable
private fun MacSetup(onConnect: () -> Unit) {
    SetupHeading(
        "Keep your archive on a Mac",
        "ArchiveBox Server.app does the saving and stores the files. ArchiveBox for Android is how you use the archive.",
    )
    Text(
        "Requires an Apple Silicon Mac with macOS 26 or later. No Docker or terminal setup needed.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    GuideStep("1", "Install the server app", "On the Mac that will store your archive, download ArchiveBox Server.app, move it to Applications, and open it.")
    GuideStep("2", "Create your account", "Open the server’s Settings from its menu-bar icon and create an administrator account.")
    GuideStep("3", "Connect this app", "Return to Connection Settings. Connect to the server, choose Get Key, and paste the API key here. The key lets this app access your archive.")
    GuideNote("Using an Android phone or another computer?", "In ArchiveBox Server’s Settings, choose Connect my devices. Keep the Mac awake and reachable over Wi-Fi or your VPN. Choose Find a server in Connection Settings, or enter the address shown on the Mac.")
    NetworkAdvice(onConnect)
}

@Composable
private fun HostingSetup() {
    SetupHeading(
        "Let a hosting service run it",
        "Your archive lives on the provider’s computers, so your home computer can be off. You pay the provider for hosting and storage.",
    )
    GuideStep("1", "Choose a provider", "Look for an ArchiveBox installation with support for version 0.9 or later and API keys, which this app requires. Confirm compatibility before paying.")
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Providers listed in the ArchiveBox README", style = MaterialTheme.typography.titleMedium)
            ExternalLink("Elestio ↗", "https://elest.io/open-source/archivebox")
            ExternalLink("PikaPods ↗", "https://www.pikapods.com/pods?run=archivebox")
            ExternalLink("Stellar Hosted ↗", "https://www.stellarhosted.com/archivebox/")
            Text(
                "Compare current prices, storage, backups, and help with setup on their sites. These are independent services; hosting is separate from this free app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    GuideStep("2", "Set up ArchiveBox with them", "Follow the provider’s setup instructions and sign in to your new ArchiveBox website. Keep its web address and your account details.")
    GuideStep("3", "Come back and connect", "Enter that web address in Connection Settings. Choose Get Key to create an API key on your server, then paste it into this app.")
    Text(
        "Renting an empty cloud computer (a VPS) is another option, but you’ll need to install and maintain ArchiveBox yourself. Use the Docker guide for that route.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DockerSetup() {
    SetupHeading(
        "Install with Docker Compose",
        "Run these commands in Terminal on the computer that will store your archive. Docker Compose is the README’s recommended container installation and includes the archiving tools.",
    )
    ExternalLink("1. Install Docker first ↗", "https://docs.docker.com/get-docker/")
    Text("2. Create a collection and start the server", style = MaterialTheme.typography.titleMedium)
    CommandCard(
        "Docker Compose commands",
        "mkdir -p ~/archivebox/data && cd ~/archivebox\n" +
            "curl -fsSL 'https://docker-compose.archivebox.io' > docker-compose.yml\n" +
            "docker compose pull\n" +
            "docker compose up -d --wait",
    )
    Text("First startup creates the collection automatically. Keep the data folder: it holds your archive.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    FinishInstallation()
}

@Composable
private fun PythonSetup() {
    SetupHeading(
        "Install the Python package",
        "Use Terminal on a Mac or Linux computer you manage. The README recommends uv to install the package in its own environment; runtime tools are installed separately.",
    )
    ExternalLink("1. Install uv first ↗", "https://docs.astral.sh/uv/getting-started/installation/")
    Text("2. Install ArchiveBox and start the server", style = MaterialTheme.typography.titleMedium)
    CommandCard(
        "ArchiveBox install commands",
        "uv tool install --python 3.13 --prerelease explicit --upgrade 'archivebox>=0.9.0rc0,<0.10'\n" +
            "mkdir -p ~/archivebox/data && cd ~/archivebox/data\n" +
            "archivebox init\n" +
            "archivebox install\n" +
            "archivebox server 0.0.0.0:5797",
    )
    Text("Already use pip? ArchiveBox is also a Python package on PyPI. Follow the installation guide for supported Python versions and dependencies. Homebrew and Debian options are in the README too.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    FinishInstallation()
}

@Composable
private fun FinishInstallation() {
    GuideStep("3", "Create your account in the browser", "On that computer, open http://admin.archivebox.localhost:5797/admin/ and follow the web setup to create your first administrator.")
    GuideStep("4", "Connect this app", "Use the server address that opens from this device. In Connection Settings, choose Get Key, sign in, create an API key, and paste it into the app.")
    NetworkAdvice()
}

@Composable
private fun NetworkAdvice(onConnect: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Wifi, null)
                Text("Connecting from another device", style = MaterialTheme.typography.titleMedium)
            }
            Text("Keep the server computer awake and reachable. A localhost address only works on the computer running the server. On your Android phone, use its network or VPN address instead; network setup may be needed first.", style = MaterialTheme.typography.bodyMedium)
            if (onConnect != null) {
                OutlinedButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Search, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Find a server in Connection Settings")
                }
            }
        }
    }
}

@Composable
private fun SetupHeader(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("guide.back")) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
            Spacer(Modifier.width(6.dp))
            Text("Back")
        }
        BrandMark()
    }
}

@Composable
private fun SetupHeading(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SetupChoice(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, route: String, onChoice: (String) -> Unit) {
    OutlinedCard(onClick = { onChoice(route) }, modifier = Modifier.fillMaxWidth().testTag("setup.$route")) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExternalLink(label: String, url: String) {
    val context = LocalContext.current
    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) {
        Icon(Icons.Outlined.OpenInNew, null)
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

@Composable
private fun GuideStep(number: String, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
            Text(number, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GuideNote(title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.NetworkWifi, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CommandCard(label: String, command: String) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectionContainer {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("guide.command"))
                }
            }
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, command))
                copied = true
            }, modifier = Modifier.testTag("guide.copy.$label")) {
                Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (copied) "Copied" else "Copy commands")
            }
        }
    }
}

@Composable
private fun ArchiveDiagram() {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val horizontal = maxWidth >= 430.dp
        Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f), shape = RoundedCornerShape(20.dp)) {
            if (horizontal) {
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    DiagramNode("Link", "Share", Icons.Outlined.Link)
                    Icon(Icons.Outlined.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    DiagramNode("Server", "Save a copy", Icons.Outlined.Storage)
                    Icon(Icons.Outlined.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    DiagramNode("Archive", "Read", Icons.Outlined.CollectionsBookmark)
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DiagramNode("Link", "Share", Icons.Outlined.Link)
                    Icon(Icons.Outlined.ArrowDownward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    DiagramNode("Server", "Save a copy", Icons.Outlined.Storage)
                    Icon(Icons.Outlined.ArrowDownward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    DiagramNode("Archive", "Read", Icons.Outlined.CollectionsBookmark)
                }
            }
        }
    }
}

@Composable
private fun DiagramNode(title: String, caption: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
