package io.archivebox.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun SetupGuide(onConnect: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
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
            Triple("Run it on your Mac", "Get ArchiveBox Server.app for Apple Silicon.", "https://github.com/ArchiveBox/ios-archivebox#your-server-your-choice"),
            Triple("Docker or a home server", "A great fit for your NAS or a spare computer.", "https://github.com/ArchiveBox/ArchiveBox#quickstart"),
            Triple("Install with Python", "Use the ArchiveBox CLI on your own machine.", "https://github.com/ArchiveBox/ArchiveBox/wiki/Install"),
            Triple("Hosting options", "Learn about running ArchiveBox on a VPS.", "https://github.com/ArchiveBox/ArchiveBox/wiki/Hosting"),
        ).forEach { (title, detail, url) ->
            OutlinedCard(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, modifier = Modifier.fillMaxWidth()) {
                ListItem(headlineContent = { Text(title) }, supportingContent = { Text(detail) }, trailingContent = { Icon(Icons.Outlined.OpenInNew, null) })
            }
        }
        Text("No tracking. No developer cloud account. Your archive stays on the server you choose.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
