package io.archivebox.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import io.archivebox.app.data.ArchiveRepository
import io.archivebox.app.ui.ArchiveBoxApp
import io.archivebox.app.ui.ArchiveTheme
import io.archivebox.app.ui.IncomingRequest

class LaunchRequestState : ViewModel() {
    val incoming = mutableStateOf<IncomingRequest?>(null)
    var initialized = false
}

class MainActivity : ComponentActivity() {
    private val launchState: LaunchRequestState by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!launchState.initialized) {
            launchState.incoming.value = IncomingRequest.from(intent)
            launchState.initialized = true
        }
        val repository = ArchiveRepository(applicationContext)
        setContent {
            ArchiveTheme {
                ArchiveBoxApp(repository, launchState.incoming.value, onConsumed = { launchState.incoming.value = null }, onFinishShare = { finish() })
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchState.incoming.value = IncomingRequest.from(intent)
    }
}
