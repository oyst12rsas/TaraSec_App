package org.tarasec.app

import org.junit.Assert.*
import org.junit.Test

class SshApprovalTargetTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test fun appLinkBindsIdentityRatherThanLocalNameOrPreviousBrowserSelection() {
        val one = SshApprovalTarget("https://example.test/ops/agent/", a).browserUrl("1".repeat(32))
        val two = SshApprovalTarget("https://example.test/ops/agent/", b).browserUrl("2".repeat(32))
        assertTrue(one.contains("server=$a&locked=1"))
        assertTrue(two.contains("server=$b&locked=1"))
        assertFalse(one.contains(b))
        assertFalse(two.contains(a))
        assertFalse(one.contains("port"))
    }
    @Test fun rejectMissingMalformedIdentityAndInsecureDestinations() {
        for ((base, ref) in listOf(
            "http://example.test/ops/agent/" to a,
            "https://user:secret@example.test/ops/agent/" to a,
            "https://example.test/ops/agent/?server=$b" to a,
            "https://example.test/ops/agent/#fragment" to a,
            "https://example.test/ops/agent/" to "",
            "https://example.test/ops/agent/" to "Roquefort"
        )) {
            try {
                SshApprovalTarget(base, ref)
                fail("Unsafe handoff was accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun verifierAndSessionTokenCannotLeakThroughDefaultDiagnostics() {
        assertFalse(SshAppPairing("test", "secret-verifier").toString().contains("secret-verifier"))
        assertFalse(SshAppGrant("secret-token", 123).toString().contains("secret-token"))
    }
}
