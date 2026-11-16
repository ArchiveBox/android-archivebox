package io.archivebox.app

import android.content.Intent
import android.webkit.CookieManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.espresso.web.assertion.WebViewAssertions.webMatches
import androidx.test.espresso.web.model.Atoms
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.archivebox.app.data.*
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.equalTo
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real WebView, encrypted storage, login page and server-issued session, with no fake API responses. */
@RunWith(AndroidJUnit4::class)
class LoginRecoveryIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun await(tag: String) = compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun loginForm() = compose.waitUntil(30_000) {
        runCatching {
            onWebView().check(webMatches(Atoms.script("return !!document.querySelector('#login-form #id_password');", Atoms.castOrDie(Boolean::class.javaObjectType)), equalTo(true)))
        }.isSuccess
    }

    @Test fun staleKeyCanOpenLoginCancelAndRecoverFromRealAdminSession() {
        val args = InstrumentationRegistry.getArguments()
        val server = normalizeServer(requireNotNull(args.getString("serverUrl")))
        val validKey = requireNotNull(args.getString("apiToken"))
        val repository = ArchiveRepository(context)
        val previous = repository.registry.value.servers.find { normalizeServer(it.server) == server }
        val target = previous?.copy(token = "expired-test-key") ?: ServerConfiguration(name = "Login recovery", server = server, token = "expired-test-key", persona = "Default")
        runBlocking { repository.saveConnection(target, select = false) }
        repository.dismissSetup()
        val beforeLogin = repository.registry.value
        try {
            context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("tab.Settings"); compose.onNodeWithTag("tab.Settings").performClick()
            await("connection.url"); compose.onNodeWithTag("connection.url").performScrollTo().performTextReplacement(server)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            fun getKey() {
                compose.onNodeWithTag("connection.getKey").performScrollTo().performClick()
                UiDevice.getInstance(instrumentation).wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_allow_button")), 2_000)?.click()
                await("connection.login"); loginForm()
                assertTrue("Get Key must persist removal before login", ArchiveRepository(context).registry.value.servers.single { it.id == target.id }.token.isEmpty())
            }
            getKey()
            compose.onNodeWithTag("connection.login.cancel").performClick()
            await("connection.getKey")
            assertTrue(ArchiveRepository(context).registry.value.servers.single { it.id == target.id }.token.isEmpty())
            compose.onNodeWithTag("history.server.${target.id}").performScrollTo().performClick()
            getKey()

            // Obtain a genuine login session from the supported server API, then navigate the real
            // admin page. This tests automatic recovery without storing a test account's password.
            val session = runBlocking { repository.api.browserSession(target.copy(token = validKey)) }
            val installed = CountDownLatch(1)
            instrumentation.runOnMainSync {
                val cookie = "${session.cookie.name}=${session.cookie.value}; Path=/; HttpOnly; SameSite=Lax" + if (session.cookie.secure) "; Secure" else ""
                CookieManager.getInstance().setCookie(session.admin_url, cookie) { installed.countDown() }
            }
            assertTrue(installed.await(10, TimeUnit.SECONDS))
            onWebView().perform(Atoms.script("location.href = ${JSONObject.quote(session.admin_url)}; return true;", Atoms.castOrDie(Boolean::class.javaObjectType)))
            compose.waitUntil(30_000) { ArchiveRepository(context).registry.value.servers.single { it.id == target.id }.token.isNotBlank() }
            val recovered = ArchiveRepository(context).registry.value
            val saved = recovered.servers.single { it.id == target.id }
            runBlocking { repository.api.testToken(saved) }
            assertEquals(target.persona, saved.persona)
            assertEquals(beforeLogin.active_server_id, recovered.active_server_id)
            assertEquals(beforeLogin.default_server_ids, recovered.default_server_ids)
            await("connection.status")
            compose.onNodeWithTag("tab.Search").performClick()
            await("browser.ready")
            onWebView().check(webMatches(Atoms.script("return !!document.querySelector('#login-form');", Atoms.castOrDie(Boolean::class.javaObjectType)), equalTo(false)))
        } finally {
            runBlocking {
                if (previous != null) {
                    if (previous.token.isNotBlank()) repository.saveConnection(previous, select = false)
                    else repository.beginServerLogin(previous.server).cancel()
                } else repository.removeConnection(target.id)
            }
        }
    }

    @Test fun cancelledAndSupersededRecoveriesCannotWriteAKey() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val repository = ArchiveRepository(context)
        val server = normalizeServer(requireNotNull(args.getString("serverUrl")))
        val key = requireNotNull(args.getString("apiToken"))
        val previous = repository.registry.value.servers.find { normalizeServer(it.server) == server }
        val target = previous ?: ServerConfiguration(name = "Recovery race", server = server, token = key)
        repository.saveConnection(target.copy(token = key), select = false)
        try {
            val cancelled = repository.beginServerLogin(server)
            cancelled.cancel()
            assertTrue(runCatching { repository.completeServerLogin(cancelled, key) }.isFailure)
            val older = repository.beginServerLogin(server)
            val current = repository.beginServerLogin(server)
            assertTrue(runCatching { repository.completeServerLogin(older, key) }.isFailure)
            repository.completeServerLogin(current, key)
            val stale = repository.beginServerLogin(server)
            // An unrelated repository instance's credential edit invalidates an older recovery.
            ArchiveRepository(context).saveConnection(target.copy(token = key), select = false)
            assertTrue(runCatching { repository.completeServerLogin(stale, key) }.isFailure)
            assertTrue(key == ArchiveRepository(context).registry.value.servers.single { it.id == target.id }.token)
        } finally {
            if (previous != null) {
                if (previous.token.isNotBlank()) repository.saveConnection(previous, select = false)
                else repository.beginServerLogin(previous.server).cancel()
            } else repository.removeConnection(target.id)
        }
    }
}
