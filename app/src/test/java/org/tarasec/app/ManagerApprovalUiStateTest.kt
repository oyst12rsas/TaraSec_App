package org.tarasec.app

import org.junit.Assert.*
import org.junit.Test

class ManagerApprovalUiStateTest {
    private fun state(
        exists: Boolean = true, known: Boolean = true, email: Boolean = true,
        admin: Boolean = false, unavailable: Boolean = false
    ) = ManagerApprovalUiState(exists, known, email, admin, unavailable)

    @Test fun noRequestCannotOpenApprovalEvenWithOldFlags() {
        val s = state(exists = false)
        assertFalse(s.canOpenAdminApproval)
        assertFalse(s.canResendEmail)
    }
    @Test fun unverifiedOrStaleStatusCannotExposeApprovalPage() {
        assertFalse(state(known = false).canOpenAdminApproval)
        assertFalse(state(known = false, email = false).canResendEmail)
    }
    @Test fun emailConfirmationComesBeforeAdministratorApproval() {
        val s = state(email = false)
        assertTrue(s.canResendEmail)
        assertFalse(s.canOpenAdminApproval)
        assertFalse(s.needsNewRequest)
    }
    @Test fun verifiedRequestAwaitingAdminShowsApprovalAction() {
        val s = state()
        assertTrue(s.canOpenAdminApproval)
        assertFalse(s.canResendEmail)
        assertFalse(s.needsNewRequest)
    }
    @Test fun alreadyApprovedRequestDoesNotOfferMoreApproval() {
        assertFalse(state(admin = true).canOpenAdminApproval)
    }
    @Test fun expiredRejectedOrRevokedRequestOffersOnlyRestart() {
        val s = state(unavailable = true)
        assertTrue(s.needsNewRequest)
        assertFalse(s.canOpenAdminApproval)
        assertFalse(s.canResendEmail)
    }
}
