package org.tarasec.app

internal object SshClientCommands {
    const val run = "~/.local/bin/tarasec-client"
    private const val download = "https://raw.githubusercontent.com/oyst12rsas/taransvar/main/misc/tarasec-client.sh"
    private const val defaultService = "https://tarasec.org/ops/agent/"

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    fun setup(gateway: String, service: String): String {
        require(gateway.isNotBlank() && service.isNotBlank())
        val options = " --install --gateway " + quote(gateway) +
            if (service == defaultService) "" else " --service " + quote(service)
        return listOf(
            "clear",
            "tarasec_client_setup=\$(mktemp) &&",
            "curl --fail --show-error --silent --proto '=https' -o \"\$tarasec_client_setup\" " + quote(download) + " &&",
            "bash \"\$tarasec_client_setup\"" + options + " &&",
            "rm -f -- \"\$tarasec_client_setup\""
        ).joinToString("\n")
    }
}
