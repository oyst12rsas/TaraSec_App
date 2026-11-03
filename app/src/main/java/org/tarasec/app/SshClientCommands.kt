package org.tarasec.app

internal object SshClientCommands {
    const val run = "~/.local/bin/tarasec-client"
    private const val download = "https://raw.githubusercontent.com/oyst12rsas/taransvar/main/misc/tarasec-client.sh"
    private const val defaultService = "https://tarasec.org/ops/agent/"

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    fun connect(gateway: String, user: String, port: Int = 5822): String {
        require(user.matches(Regex("[a-z_][a-z0-9_-]{0,31}")) && port in 1..65535)
        require(gateway.isNotBlank())
        return run + " --connect --gateway " + quote(gateway) + " --user " + quote(user) +
            if (port == 5822) "" else " --port " + port
    }

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
