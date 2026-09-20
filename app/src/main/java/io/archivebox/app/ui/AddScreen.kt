package io.archivebox.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.archivebox.app.data.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.launch

internal class SubmissionState(initialText: String, initialPersona: String) : ViewModel() {
    val text = mutableStateOf(initialText)
    val tags = mutableStateOf<List<String>>(emptyList())
    val tagDraft = mutableStateOf("")
    val persona = mutableStateOf(initialPersona)
    val busy = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val tagError = mutableStateOf<String?>(null)
    val receipt = mutableStateOf<Receipt?>(null)
    val tagsSaved = mutableStateOf(false)
    val removing = mutableStateOf(false)
    val removed = mutableStateOf(false)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AddScreen(repository: ArchiveRepository, connection: Connection?, initialText: String, onConnect: () -> Unit, onDone: (() -> Unit)? = null, requestId: String = "main", onPreventDismiss: (Boolean) -> Unit = {}) {
    if (connection == null) { ConnectPrompt(onConnect); return }
    val model: SubmissionState = viewModel(key = "submission:${connection.server}:${connection.token.hashCode()}:$requestId") { SubmissionState(initialText, connection.persona) }
    val scope = model.viewModelScope
    // Shared content and submission receipts remain in memory; there is no offline submission queue.
    var text by model.text
    var tags by model.tags
    var tagDraft by model.tagDraft
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var personas by remember { mutableStateOf<List<Persona>>(emptyList()) }
    var persona by model.persona
    var personaMenu by remember { mutableStateOf(false) }
    var busy by model.busy
    var error by model.error
    var tagError by model.tagError
    var receipt by model.receipt
    var tagsSaved by model.tagsSaved
    var removing by model.removing
    var removed by model.removed
    var confirmRemoval by remember { mutableStateOf(false) }
    SideEffect { onPreventDismiss(busy || removing || !removed && receipt != null && (!tagsSaved || tagDraft.isNotBlank())) }
    LaunchedEffect(connection.server) {
        suggestions = repository.recentTags(connection.server)
        try { personas = repository.api.personas(connection) } catch (_: Exception) { /* Server default remains usable when persona listing is restricted. */ }
    }
    suspend fun saveTags() {
        val accepted = receipt ?: return
        busy = true; tagError = null; tagsSaved = false
        try {
            repository.api.updateTags(connection, accepted, tags)
            repository.rememberTags(connection.server, tags)
            suggestions = repository.recentTags(connection.server)
            tagsSaved = true
        } catch (e: Exception) { tagError = e.message ?: "Couldn't save tags." }
        finally { busy = false }
    }
    fun addDraft() {
        tags = normalizeTags(tags + normalizeTags(tagDraft)); tagDraft = ""; tagsSaved = false
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(color = if (receipt != null || removed) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (removed) Icons.Outlined.DeleteOutline else if (receipt != null) Icons.Outlined.CheckCircle else Icons.Outlined.BookmarkAdd, null, Modifier.size(38.dp))
                Text(if (removed) "Removed from server" else if (receipt != null) "Saved to your server" else "Worth keeping.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag(if (removed) "share.undone" else if (receipt != null) "share.accepted" else "add.heading"))
                Text(if (removed) "This submission was removed. Previous captures are kept." else if (receipt != null) "ArchiveBox has accepted your links. Your server will capture them in the background." else "Save links now and revisit them long after the web moves on.")
                Text(connection.server, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (!removed) {
            if (receipt == null) {
                OutlinedTextField(text, { text = it }, label = { Text("URLs to archive") }, placeholder = { Text("https://example.com/article\nOne link per line, or paste shared text") }, minLines = 3, maxLines = 7, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("add.urls"))
                val count = extractUrls(text).size
                Text(if (count == 1) "1 link ready to save" else "$count links ready to save", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                receipt!!.urls.forEach { url -> Text(url, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("share.url")) }
            }
            SectionTitle("Organize with tags")
            val domain = extractUrls(text).firstOrNull()?.toHttpUrlOrNull()?.let { it.topPrivateDomain() ?: it.host }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = "⭐️" in tags, onClick = { tags = if ("⭐️" in tags) tags - "⭐️" else tags + "⭐️"; tagsSaved = false }, enabled = !busy, label = { Text("Favorite") }, leadingIcon = { Icon(Icons.Outlined.StarOutline, null, Modifier.size(18.dp)) }, modifier = Modifier.testTag("add.favorite"))
                if (domain != null && domain !in tags) SuggestionChip(onClick = { tags = normalizeTags(tags + domain); tagsSaved = false }, enabled = !busy, label = { Text(domain) }, modifier = Modifier.testTag("add.domainTag"))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().testTag("add.selectedTags")) {
                tags.forEach { tag -> InputChip(selected = true, onClick = { if (!busy) { tags = tags - tag; tagsSaved = false } }, enabled = !busy, label = { Text(tag) }, trailingIcon = { Icon(Icons.Outlined.Close, "Remove tag $tag", Modifier.size(16.dp)) }, modifier = Modifier.testTag("tag.$tag")) }
            }
            OutlinedTextField(tagDraft, { tagDraft = it }, label = { Text("Add tags, separated by commas") }, singleLine = true, enabled = !busy, keyboardActions = KeyboardActions(onDone = { addDraft() }), modifier = Modifier.fillMaxWidth().testTag("add.tags"), trailingIcon = { IconButton(onClick = { addDraft() }, enabled = tagDraft.isNotBlank() && !busy, modifier = Modifier.testTag("add.tagsConfirm")) { Icon(Icons.Outlined.Add, "Add tags") } })
            if (suggestions.any { it !in tags }) {
                Text("Recently used", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("add.tagSuggestions")) {
                    suggestions.filter { it !in tags }.forEach { tag -> SuggestionChip(onClick = { tags = tags + tag; tagsSaved = false }, enabled = !busy, label = { Text(tag) }, icon = { Icon(Icons.Outlined.Add, "Add suggested tag $tag", Modifier.size(16.dp)) }) }
                }
            }
            if (receipt == null) {
                SectionTitle("Persona")
                Box {
                    OutlinedButton(onClick = { personaMenu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("add.persona")) { Icon(Icons.Outlined.AccountCircle, null); Spacer(Modifier.width(8.dp)); Text(persona); Spacer(Modifier.weight(1f)); Icon(Icons.Outlined.ExpandMore, null) }
                    DropdownMenu(expanded = personaMenu, onDismissRequest = { personaMenu = false }) {
                        (listOf("Default") + personas.map { it.name }).distinct().forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { persona = name; personaMenu = false; scope.launch { runCatching { repository.saveConnection(connection.copy(persona = name)) }.onFailure { error = it.message } } }) }
                    }
                }
                Text("Choose a server persona to capture pages that need a login.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { ErrorCard(it) }
            tagError?.let { ErrorCard("Links saved; tags weren't saved. $it") }
            if (tagsSaved && receipt != null) Text("Tags saved", color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.testTag("share.tagsSaved"))
            if (busy || removing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (receipt == null) {
                Button(onClick = {
                    addDraft()
                    scope.launch {
                        busy = true; error = null
                        try {
                            val urls = extractUrls(text)
                            require(urls.isNotEmpty()) { "Add at least one HTTP or HTTPS URL." }
                            receipt = repository.api.submit(connection.copy(persona = persona), urls)
                            if (tags.isNotEmpty()) saveTags()
                        } catch (e: Exception) { error = "Submission could not be confirmed. Check your server before trying again. ${e.message.orEmpty()}" }
                        finally { busy = false }
                    }
                }, enabled = extractUrls(text).isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("add.save")) { Icon(Icons.Outlined.BookmarkAdd, null); Spacer(Modifier.width(8.dp)); Text(if (busy) "Saving…" else "Save to ArchiveBox") }
            } else {
                if (!tagsSaved || tagDraft.isNotBlank() || tagError != null) Button(onClick = { addDraft(); scope.launch { saveTags() } }, enabled = !busy && !removing, modifier = Modifier.fillMaxWidth().testTag("share.saveTags")) { Text(if (tagError != null) "Retry saving tags" else "Save tags") }
                Button(onClick = {
                    scope.launch {
                        if (tagDraft.isNotBlank()) addDraft()
                        if (!tagsSaved) saveTags()
                        if (tagError == null) {
                            if (onDone != null) onDone() else { receipt = null; text = ""; tags = emptyList(); tagsSaved = false }
                        }
                    }
                }, enabled = !busy && !removing, modifier = Modifier.fillMaxWidth().testTag("share.done")) { Text(if (onDone != null) "Done" else "Save another link") }
                TextButton(onClick = { confirmRemoval = true }, enabled = !busy && !removing, modifier = Modifier.testTag("share.undo")) { Icon(Icons.Outlined.Undo, null); Spacer(Modifier.width(8.dp)); Text("Undo this submission") }
            }
        } else Button(onClick = { if (onDone != null) onDone() else { removed = false; receipt = null; text = ""; tags = emptyList() } }, modifier = Modifier.testTag("share.done")) { Text("Done") }
    }
    if (confirmRemoval) AlertDialog(onDismissRequest = { confirmRemoval = false }, title = { Text("Remove this submission?") }, text = { Text("Stops this crawl and removes the links submitted by this share. Previous captures of the same URLs are kept.") }, confirmButton = {
        TextButton(onClick = { confirmRemoval = false; scope.launch { removing = true; error = null; try { repository.api.removeSubmission(connection, receipt!!); removed = true } catch (e: Exception) { error = "Removal wasn't confirmed. ${e.message.orEmpty()}" } finally { removing = false } } }) { Text("Remove from server") }
    }, dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("Keep it") } })
}
