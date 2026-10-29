package org.tarasec.app

import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class SshSessionStoreTest {
    private val target = SshApprovalTarget("https://account.example/ops/agent/", "a".repeat(64))
    private val identity = "https://account.example/api/v1/identity"
    @After fun clear() = SshSessionStore.clear()

    @Test fun navigationRetainsGrantAndMatchingJobUntilExpiry() {
        val key = SshSessionKey(SshSessionStore.owner("installation", identity, "account-token"), target)
        val session = SshSession(grant = SshAppGrant("secret", 200), jobId = "matching-job")
        SshSessionStore.put(key, session)
        assertEquals(session, SshSessionStore.read(key, 100))
        assertEquals("matching-job", SshSessionStore.read(key, 199)?.jobId)
        assertNull(SshSessionStore.read(key, 200))
    }
    @Test fun nodeOriginAccountAndInstallationCannotShareAGrant() {
        val owner = SshSessionStore.owner("one", identity, "first")
        val key = SshSessionKey(owner, target)
        SshSessionStore.put(key, SshSession(grant = SshAppGrant("secret", 200)))
        for (other in listOf(
            SshSessionKey(SshSessionStore.owner("two", identity, "first"), target),
            SshSessionKey(SshSessionStore.owner("one", identity, "second"), target),
            SshSessionKey(owner, SshApprovalTarget(target.base, "b".repeat(64))),
            SshSessionKey(owner, SshApprovalTarget("https://other.example/ops/agent/", target.serverRef))
        )) assertNull(SshSessionStore.read(other, 100))
        assertFalse(owner.contains("first"))
        SshSessionStore.clear()
        assertNull(SshSessionStore.read(key, 100))
        assertNotEquals(owner, SshSessionStore.owner("one", identity, "first"))
    }
    @Test fun browserPairingSurvivesNavigationButExpires() {
        val key = SshSessionKey("owner", target)
        val session = SshSession(pairing = SshAppPairing("id", "verifier"), pairingUntil = 200)
        SshSessionStore.put(key, session)
        assertEquals(session, SshSessionStore.read(key, 100))
        assertNull(SshSessionStore.read(key, 200))
        assertFalse(session.toString().contains("verifier"))
    }
    @Test fun accountCredentialNeverGoesToNodeSuppliedForeignOrigin() {
        assertTrue(SshSessionStore.trustsAccount(target, identity))
        assertTrue(SshSessionStore.trustsAccount(target, "https://account.example:443/api/v1/identity"))
        for (other in listOf("http://account.example/api", "https://other.example/api",
            "https://account.example:444/api", "https://user@account.example/api",
            "https://account.example/api?redirect=foreign", "https://account.example/api#foreign")) {
            assertFalse(other, SshSessionStore.trustsAccount(target, other))
        }
    }
}
