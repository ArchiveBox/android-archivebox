package io.archivebox.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.archivebox.app.data.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun SearchScreen(repository: ArchiveRepository, connection: Connection?, initialQuery: String, onConnect: () -> Unit, onOpen: (Snapshot) -> Unit) {
    if (connection == null) { ConnectPrompt(onConnect); return }
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    var results by remember(connection) { mutableStateOf<List<Snapshot>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var committedQuery by remember { mutableStateOf(initialQuery) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    suspend fun search(append: Boolean = false) {
        busy = true; error = null
        try {
            if (!append) committedQuery = query
            val page = repository.api.snapshots(connection, committedQuery, if (append) results.size else 0)
            results = if (append) (results + page).distinctBy { it.id } else page
            more = page.size == 50; searched = true
        } catch (e: Exception) { error = e.message ?: "Couldn't search your archive." }
        finally { busy = false }
    }
    LaunchedEffect(connection, initialQuery) { search() }
    LazyColumn(Modifier.fillMaxSize().testTag("search"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            OutlinedTextField(query, { query = it }, label = { Text("Search titles, URLs, and tags") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = { IconButton(onClick = { scope.launch { search() } }, enabled = !busy, modifier = Modifier.testTag("search.submit")) { Icon(Icons.Outlined.ArrowForward, "Search") } }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { scope.launch { search() } }), modifier = Modifier.fillMaxWidth().testTag("search.query"))
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { message -> item { ErrorCard(message); TextButton(onClick = { scope.launch { search() } }) { Text("Try again") } } }
        if (searched && results.isEmpty() && !busy && error == null) item {
            Column(Modifier.padding(vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Outlined.SearchOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary); Text("No saved pages found", style = MaterialTheme.typography.headlineSmall); Text("Try another title, URL, or tag, or save your first link from the Add tab.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (results.isNotEmpty()) item { Text(if (committedQuery.isBlank()) "Your latest saved pages" else "Results for “$committedQuery”", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("search.results")) }
        items(results, key = { it.id }) { snapshot ->
            Card(onClick = { onOpen(snapshot) }, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth().testTag("search.result.${snapshot.id}")) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Outlined.Language, null, tint = MaterialTheme.colorScheme.primary); Text(snapshot.title.ifBlank { snapshot.url }, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) }
                    Text(snapshot.url, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { snapshot.tags.forEach { tag -> SuggestionChip(onClick = { query = tag; scope.launch { search() } }, label = { Text(tag) }, icon = { Icon(Icons.Outlined.Sell, null, Modifier.size(14.dp)) }) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onOpen(snapshot) }) { Text("Open saved page"); Spacer(Modifier.width(6.dp)); Icon(Icons.Outlined.ArrowOutward, null, Modifier.size(16.dp)) }
                        IconButton(onClick = {
                            val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, snapshot.url).putExtra(Intent.EXTRA_TITLE, snapshot.title)
                            context.startActivity(Intent.createChooser(intent, "Share original URL"))
                        }) { Icon(Icons.Outlined.Share, "Share original URL") }
                        IconButton(onClick = {
                            val link = "${connection.server.trimEnd('/')}/snapshot/${snapshot.id}/index.html"
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), "Share archived page"))
                        }) { Icon(Icons.Outlined.Link, "Share archived page") }
                    }
                }
            }
        }
        if (more) item { OutlinedButton(onClick = { scope.launch { search(true) } }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("search.more")) { Text("Load more") } }
    }
}
