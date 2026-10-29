package org.tarasec.app

import java.net.URI
import java.security.MessageDigest

// Process memory only. Node identity, approval origin, installation and account
// all participate in the key; a display name or gateway IP cannot grant access.
data class SshSessionKey(val owner: String, val target: SshApprovalTarget)
data class SshSession(val grant: SshAppGrant? = null, val pairing: SshAppPairing? = null,
    val pairingUntil: Long = 0, val jobId: String = "") {
    override fun toString() = "SshSession(credentials=omitted)"
}

object SshSessionStore {
    @Volatile private var generation = 0L
    private val entries = mutableMapOf<SshSessionKey, SshSession>()

    fun owner(installationId: String, identity: String, token: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((token ?: "no-account").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$generation|$installationId|$identity|$digest"
    }

    fun trustsAccount(target: SshApprovalTarget, identity: String): Boolean = runCatching {
        val account = URI(identity)
        val approval = URI(target.base)
        account.scheme == "https" && !account.host.isNullOrBlank() &&
            account.rawUserInfo == null && account.rawQuery == null && account.rawFragment == null &&
            account.host.equals(approval.host, ignoreCase = true) &&
            (if (account.port == -1) 443 else account.port) ==
                (if (approval.port == -1) 443 else approval.port)
    }.getOrDefault(false)

    @Synchronized fun read(key: SshSessionKey, now: Long = System.currentTimeMillis() / 1000): SshSession? {
        val entry = entries[key] ?: return null
        if (entry.grant != null && entry.grant.expiresAt <= now ||
            entry.pairing != null && entry.pairingUntil <= now) {
            entries.remove(key)
            return null
        }
        return entry
    }
    @Synchronized fun put(key: SshSessionKey, session: SshSession) { entries[key] = session }
    @Synchronized fun remove(key: SshSessionKey) { entries.remove(key) }
    @Synchronized fun clear() { entries.clear(); generation++ }
}
