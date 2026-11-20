package io.archivebox.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.archivebox.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Passwords stay in memory; the API key never travels through a URL or WebView. */
@Composable internal fun RecoverySignIn(repository: ArchiveRepository, saved: ServerConfiguration, onDismiss: () -> Unit, onRecovered: () -> Unit) {
    val context = LocalContext.current
    val supportsPassword = supportsPasswordSignIn(saved.server)
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    fun dismiss() { job?.cancel(); password = ""; onDismiss() }
    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text("Sign in again") },
        text = { Column(Modifier.testTag("recovery.login"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your server rejected its saved API key. Sign in to replace it. Your saved connection stays unchanged until verification succeeds.")
            Text(saved.server)
            if (!supportsPassword) Text("Password sign-in requires HTTPS. Use browser Get Key and enter the verified key in Settings instead.")
            OutlinedTextField(username, { username = it }, enabled = !busy && supportsPassword, singleLine = true, label = { Text("Username") }, modifier = Modifier.testTag("recovery.username"))
            OutlinedTextField(password, { password = it }, enabled = !busy && supportsPassword, singleLine = true, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.testTag("recovery.password"))
            error?.let { Text(it, modifier = Modifier.testTag("recovery.error")) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(enabled = !busy, onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(keyRecoveryUrl(saved.server)))) }
                    .onSuccess { dismiss() }.onFailure { error = "Could not open your browser." }
            }) { Text("Browser sign-in / SSO: get a key manually") }
        } },
        confirmButton = { Button(enabled = supportsPassword && !busy && username.isNotBlank() && password.isNotEmpty(), modifier = Modifier.testTag("recovery.submit"), onClick = {
            val requestedUsername = username
            val requestedPassword = password
            busy = true; error = null
            job = scope.launch {
                try {
                    val token = repository.api.signIn(saved.server, requestedUsername, requestedPassword)
                    repository.api.testToken(saved.copy(token = token))
                    repository.replaceRejectedKey(saved, token)
                    password = ""
                    onRecovered()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "Sign-in could not be completed. Check your credentials or use browser sign-in. Your saved connection is unchanged." }
                finally { busy = false }
            }
        }) { Text("Sign in & save") } },
        dismissButton = { TextButton(onClick = { dismiss() }, modifier = Modifier.testTag("recovery.cancel")) { Text("Cancel") } },
    )
}
