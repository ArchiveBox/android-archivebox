package io.archivebox.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import io.archivebox.app.data.ArchiveRepository
import io.archivebox.app.ui.ArchiveBoxApp
import io.archivebox.app.ui.ArchiveTheme
import io.archivebox.app.ui.IncomingRequest

class MainActivity : ComponentActivity() {
    private val incoming = mutableStateOf<IncomingRequest?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incoming.value = IncomingRequest.from(intent)
        val repository = ArchiveRepository(applicationContext)
        setContent {
            ArchiveTheme {
                ArchiveBoxApp(repository, incoming.value, onConsumed = { incoming.value = null }, onFinishShare = { finish() })
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming.value = IncomingRequest.from(intent)
    }
}
