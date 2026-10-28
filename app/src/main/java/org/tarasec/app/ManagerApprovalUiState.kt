package org.tarasec.app

/** Actions are derived from the last verified request state, not screen order. */
data class ManagerApprovalUiState(
    val requestExists: Boolean,
    val statusKnown: Boolean,
    val emailVerified: Boolean,
    val gatewayApproved: Boolean,
    val unavailable: Boolean
) {
    val needsNewRequest: Boolean get() = requestExists && unavailable
    val canOpenAdminApproval: Boolean get() =
        requestExists && statusKnown && !unavailable && emailVerified && !gatewayApproved
    val canResendEmail: Boolean get() =
        requestExists && statusKnown && !unavailable && !emailVerified
}
