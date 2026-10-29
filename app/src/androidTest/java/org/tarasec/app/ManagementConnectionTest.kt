package org.tarasec.app

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.ServerSocket
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagementConnectionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun clearSavedAccess() {
        listOf("tarasec_installations_v1", "tarasec_manager_credentials_v1").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @After fun close() {
        scenario?.close()
        clearSavedAccess()
    }

    private fun openInstallation(withCredential: Boolean) {
        // Reserve then release an unused loopback port: fail promptly without
        // contacting a real installation or sending a real credential.
        val port = ServerSocket(0).use { it.localPort }
        val item = InstallationStore.register(context, "Test node", "http://127.0.0.1:$port", "127.0.0.1")
        if (withCredential) SecureCredentialStore.put(context, item.id, "test-only-credential")
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
    }

    private fun assertManagementTabsHidden() {
        listOf("Status", "AI", "Assistance", "SSH").forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test fun missingCredentialShowsSetupInsteadOfDeadReconnectButtons() {
        openInstallation(withCredential = false)
        compose.onNodeWithText("Connect to Test node").assertIsDisplayed()
        compose.onNodeWithText("Set up management access").assertIsEnabled()
        compose.onNodeWithTag("managementReconnect").assertDoesNotExist()
        assertManagementTabsHidden()
        compose.onNodeWithText("Set up management access").performClick()
        compose.onNodeWithText("Request manager access").assertIsEnabled()
    }

    @Test fun savedCredentialShowsFreshGoogleGateWithoutServerData() {
        val server = ServerSocket(0)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val sensitive = java.util.concurrent.atomic.AtomicInteger(0)
        val worker = Thread {
            while (running.get()) {
                try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val line = reader.readLine().orEmpty()
                        var length = 0
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                        }
                        repeat(length) { reader.read() }
                        val login = line.startsWith("POST /script/managerAuth.php ")
                        if (!line.contains("/script/managerAuth.php")) sensitive.incrementAndGet()
                        val body = if (login) org.json.JSONObject().put("ok", false).put("error", "manager_google_required")
                            .put("binding", "b".repeat(64)).put("approvalTarget", org.json.JSONObject()
                                .put("approval_url", "https://example.test/ops/agent/").put("server_ref", "a".repeat(64))).toString()
                            else """{"ok":true,"authenticated":false}"""
                        val status = if (login) "403 Forbidden" else "200 OK"
                        val response = "HTTP/1.1 " + status + "\r\nContent-Type: application/json\r\nContent-Length: " +
                            body.toByteArray().size + "\r\nConnection: close\r\n\r\n" + body
                        socket.getOutputStream().write(response.toByteArray())
                    }
                } catch (_: Exception) { if (running.get()) sensitive.incrementAndGet() }
            }
        }.apply { isDaemon = true; start() }
        try {
            val item = InstallationStore.register(context, "Test node", "http://127.0.0.1:" + server.localPort, "127.0.0.1")
            SecureCredentialStore.put(context, item.id, "test-only-credential")
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            compose.waitUntil(20_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasText("Sign in with Google to manage this server"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Sign in with Google to manage this server").assertIsDisplayed()
            assertManagementTabsHidden()
            org.junit.Assert.assertEquals(0, sensitive.get())
        } finally { running.set(false); server.close(); worker.join(1000) }
    }

    @Test fun failedAutomaticConnectionAndRetryBothShowTheError() {
        openInstallation(withCredential = true)
        fun awaitFailure() {
            compose.waitUntil(20_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasText("Management connection failed:", substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
        awaitFailure()
        compose.onNodeWithTag("managementConnectionStatus").assertIsDisplayed()
        compose.onNodeWithTag("managementReconnect").assertIsEnabled()
        assertManagementTabsHidden()
        compose.onNodeWithTag("managementReconnect").performClick()
        awaitFailure()
        compose.onNodeWithTag("managementReconnect").assertIsEnabled()
        assertManagementTabsHidden()
    }
}
