package org.tarasec.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MentalHealthReply(val text: String, val chatId: String?)

object MentalHealthFlowiseClient {
    private const val ENDPOINT = "https://tarasec.org/api/v1/mental-health/chat.php"

    val isConfigured: Boolean
        get() = true

    fun send(message: String, chatId: String?): MentalHealthReply {
        val payload = JSONObject().apply {
            put("question", message.take(4000))
            if (!chatId.isNullOrBlank()) put("chat_id", chatId.take(200))
        }

        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 90_000
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw IllegalStateException("TaraSec AI returned HTTP $code")
            }
            if (code !in 200..299 || !json.optBoolean("ok", false)) {
                throw IllegalStateException(
                    json.optString("reason", "TaraSec AI returned HTTP $code").replace('_', ' ')
                )
            }

            val text = json.optString("text").trim()
            if (text.isBlank()) throw IllegalStateException("TaraSec AI returned an empty reply")
            val returnedChatId = json.optString("chat_id").takeIf { it.isNotBlank() }
            return MentalHealthReply(text, returnedChatId ?: chatId)
        } finally {
            connection.disconnect()
        }
    }
}
