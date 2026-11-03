package org.tarasec.app

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SshClientCommandsTest {
    private fun executeSetup(gateway: String, service: String): List<String> {
        assumeTrue(java.io.File("/bin/bash").exists())
        val root = Files.createTempDirectory("tarasec commands ")
        try {
            val bin = Files.createDirectory(root.resolve("bin"))
            val capture = root.resolve("arguments")
            val curl = "#!/usr/bin/env bash\nwhile ((\$#)); do\nif [[ \$1 == -o ]]; then output=\$2; shift 2; else shift; fi\ndone\n" +
                "printf '%s\\n' '#!/usr/bin/env bash' 'printf \"%s\\n\" \"\$@\" > \"\$TARASEC_ARGS_CAPTURE\"' > \"\$output\"\n"
            Files.writeString(bin.resolve("curl"), curl)
            bin.resolve("curl").toFile().setExecutable(true)
            Files.writeString(bin.resolve("clear"), "#!/bin/sh\nexit 0\n")
            bin.resolve("clear").toFile().setExecutable(true)
            val process = ProcessBuilder("bash", "-c", SshClientCommands.setup(gateway, service))
                .redirectErrorStream(true)
            process.environment()["PATH"] = bin.toString() + ":" + System.getenv("PATH")
            process.environment()["HOME"] = root.toString()
            process.environment()["TARASEC_ARGS_CAPTURE"] = capture.toString()
            val child = process.start()
            val output = child.inputStream.bufferedReader().readText()
            assertEquals(output, 0, child.waitFor())
            return Files.readAllLines(capture)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test fun setupFillsOnlyRequiredServerOptionForDefaultService() {
        assertEquals(listOf("--install", "--gateway", "http://100.68.165.190"),
            executeSetup("http://100.68.165.190", "https://tarasec.org/ops/agent/"))
    }

    @Test fun generatedSetupQuotesServerAndCustomServiceAsSingleArguments() {
        val gateway = "http://gateway/' ; printf injected; #"
        val service = "https://approval.test/ops/agent/?literal=\$(printf injected)"
        assertEquals(listOf("--install", "--gateway", gateway, "--service", service),
            executeSetup(gateway, service))
    }

    @Test fun installedClientRunUsesNoArgumentsAndExpandsHome() {
        assumeTrue(java.io.File("/bin/bash").exists())
        val home = Files.createTempDirectory("tarasec client home ")
        try {
            val client = home.resolve(".local/bin/tarasec-client")
            Files.createDirectories(client.parent)
            Files.writeString(client, "#!/bin/bash\nprintf '%s' \"\$#\"\n")
            client.toFile().setExecutable(true)
            val process = ProcessBuilder("bash", "-c", SshClientCommands.run)
            process.environment()["HOME"] = home.toString()
            val child = process.start()
            assertEquals("0", child.inputStream.bufferedReader().readText())
            assertEquals(0, child.waitFor())
        } finally {
            Files.walk(home).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
