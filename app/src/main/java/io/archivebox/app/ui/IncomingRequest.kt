package io.archivebox.app.ui

import android.content.Intent
import java.util.UUID

/** Untrusted external requests only fill a screen; they never submit or replace credentials. */
data class IncomingRequest(
    val action: String,
    val text: String = "",
    val server: String? = null,
    val token: String? = null,
    val snapshot: String? = null,
    val externalShare: Boolean = false,
    val nonce: String = UUID.randomUUID().toString(),
) {
    companion object {
        fun from(intent: Intent?): IncomingRequest? {
            intent ?: return null
            if (intent.action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_PROCESS_TEXT)) {
                val text = if (intent.action == Intent.ACTION_PROCESS_TEXT) {
                    intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
                } else {
                    val values = mutableListOf<String>()
                    if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                        intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.let { texts -> values += texts.map { it.toString() } }
                    } else intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.let { values += it.toString() }
                    intent.clipData?.let { clip ->
                        (0 until clip.itemCount).forEach { index ->
                            val item = clip.getItemAt(index)
                            item.text?.let { values += it.toString() }
                            item.uri?.takeIf { it.scheme in listOf("http", "https") }?.let { values += it.toString() }
                        }
                    }
                    values.distinct().joinToString("\n")
                }
                return IncomingRequest("add", text.take(200_000), externalShare = true)
            }
            if (intent.action == Intent.ACTION_SEARCH) return IncomingRequest("search", intent.getStringExtra("query").orEmpty())
            val uri = intent.data ?: return null
            if (uri.scheme != "archivebox") return null
            return when (uri.host) {
                "add" -> IncomingRequest("add", uri.getQueryParameter("url") ?: uri.getQueryParameter("urls").orEmpty())
                "search" -> IncomingRequest("search", uri.getQueryParameter("q") ?: uri.getQueryParameter("query").orEmpty())
                "connect" -> IncomingRequest("connect", server = uri.getQueryParameter("server") ?: uri.getQueryParameter("url"), token = uri.getQueryParameter("api_key") ?: uri.getQueryParameter("token"))
                "snapshot" -> IncomingRequest("snapshot", server = uri.getQueryParameter("server"), snapshot = uri.getQueryParameter("id") ?: uri.pathSegments.firstOrNull())
                else -> null
            }
        }
    }
}
