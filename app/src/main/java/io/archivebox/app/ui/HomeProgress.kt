package io.archivebox.app.ui

import android.os.SystemClock
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.archivebox.app.data.ArchiveRepository
import io.archivebox.app.data.BrowserSession
import io.archivebox.app.data.HomeProgress
import io.archivebox.app.data.HomeProgressScopeDenied
import io.archivebox.app.data.HomeProgressSessionRejected
import io.archivebox.app.data.HomeProgressUnavailable
import io.archivebox.app.data.ServerConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal data class HomeProgressState(
    val data: HomeProgress? = null,
    val refreshedAt: Long? = null,
    val latencyMs: Long? = null,
    val offline: Boolean = false,
    val unavailable: Boolean = false,
)

@Composable internal fun rememberHomeProgress(
    repository: ArchiveRepository, connection: ServerConfiguration?, visible: Boolean,
): HomeProgressState {
    val owner = LocalLifecycleOwner.current
    var state by remember(connection) { mutableStateOf(HomeProgressState()) }
    var session by remember(connection) { mutableStateOf<BrowserSession?>(null) }
    var sessionProven by remember(connection) { mutableStateOf(false) }
    LaunchedEffect(repository, connection, owner, visible) {
        if (!visible || connection == null || connection.token.isBlank()) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    val current = session?.takeIf { it.cookie.expires > System.currentTimeMillis() / 1000.0 + 15 }
                        ?: run {
                            sessionProven = false
                            repository.api.homeBrowserSession(connection).also { session = it }
                        }
                    val started = SystemClock.elapsedRealtime()
                    val data = repository.api.homeProgress(current)
                    sessionProven = true
                    val now = SystemClock.elapsedRealtime()
                    state = HomeProgressState(data, now, now - started)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: HomeProgressScopeDenied) {
                    // The endpoint was reached but this saved key cannot mint an admin session.
                    // Stop this visible polling run; reopening Home can retry after a role change.
                    state = state.copy(offline = false, unavailable = true, latencyMs = null)
                    break
                } catch (_: HomeProgressSessionRejected) {
                    // Core also returns 403 for a missing Django session. Refresh once if the
                    // rejected cookie worked before; a newly minted refused cookie must not loop.
                    val renew = sessionProven
                    session = null
                    sessionProven = false
                    state = state.copy(offline = false, unavailable = true, latencyMs = null)
                    if (!renew) break
                    delay(5_000)
                    continue
                } catch (_: HomeProgressUnavailable) {
                    state = state.copy(offline = false, unavailable = true, latencyMs = null)
                } catch (_: Exception) {
                    state = state.copy(offline = true, unavailable = false, latencyMs = null)
                }
                delay(if (state.unavailable) 30_000 else 5_000)
            }
        }
    }
    return state
}

internal fun lastRefreshedLabel(elapsedMs: Long?): String {
    val seconds = (elapsedMs ?: return "Not refreshed yet").coerceAtLeast(0) / 1_000
    val elapsed = when {
        seconds >= 3_600 -> "${seconds / 3_600}h"
        seconds >= 60 -> "${seconds / 60}m"
        else -> "${seconds}s"
    }
    return "Last refreshed $elapsed ago…"
}

@Composable internal fun HomeProgressStrip(state: HomeProgressState, onClick: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var now by remember(state.refreshedAt, state.offline) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.offline, state.refreshedAt, owner) {
        if (!state.offline || state.refreshedAt == null) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(1_000)
            }
        }
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("home.progress"),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.data?.let { data ->
                val count = data.activeCrawls
                Text(if (count == 1) "1 active crawl" else "$count active crawls", style = MaterialTheme.typography.titleSmall)
                data.latestCrawl?.let {
                    Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                val collection = listOfNotNull(
                    data.snapshots?.let { "%,d snapshots".format(it) },
                    data.archiveBytes?.let { Formatter.formatShortFileSize(context, it) },
                )
                if (collection.isNotEmpty()) Text(collection.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    when {
                        state.offline -> "Offline"
                        state.unavailable -> "Progress unavailable"
                        state.refreshedAt != null -> "● Connected"
                        else -> "Connecting…"
                    },
                    color = when {
                        state.offline -> MaterialTheme.colorScheme.error
                        !state.unavailable && state.refreshedAt != null -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
                if (!state.offline) state.latencyMs?.let { Text("$it ms", style = MaterialTheme.typography.labelMedium) }
            }
            if (state.offline) Text(
                lastRefreshedLabel(state.refreshedAt?.let { now - it }),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("home.lastRefreshed"),
            )
        }
    }
}
