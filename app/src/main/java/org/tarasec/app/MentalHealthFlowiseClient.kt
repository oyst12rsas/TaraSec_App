package org.tarasec.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MentalHealthReply(val text: String, val chatId: String?)

object MentalHealthFlowiseClient {
    private const val FLOWISE_URL = "https://YOUR-FLOWISE-HOST/api/v1/prediction/YOUR-CHATFLOW-ID"
    private const val FLOWISE_API_KEY = "DUMMY_REPLACE_ME"

    fun send(message: String, chatId: String?): MentalHealthReply {
        require(!FLOWISE_URL.contains("YOUR-FLOWISE-HOST")) {
            "Mental-health Flowise endpoint is not configured in TaraSec yet."
        }

        val payload = JSONObject().apply {
            put("question", message)
            if (!chatId.isNullOrBlank()) {
                put("overrideConfig", JSONObject().put("sessionId", chatId))
            }
        }

        val connection = URL(FLOWISE_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 90_000
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            if (FLOWISE_API_KEY.isNotBlank() && FLOWISE_API_KEY != "DUMMY_REPLACE_ME") {
                connection.setRequestProperty("Authorization", "Bearer $FLOWISE_API_KEY")
            }
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("Flowise returned HTTP $code: ${body.take(300)}")
            }

            val json = JSONObject(body)
            val text = json.optString("text", "").ifBlank { json.optString("answer", "") }
            if (text.isBlank()) throw IllegalStateException("Flowise response did not contain text.")

            val returnedChatId = json.optString("chatId", "").ifBlank { json.optString("sessionId", "") }
            return MentalHealthReply(text, returnedChatId.takeIf { it.isNotBlank() } ?: chatId)
        } finally {
            connection.disconnect()
        }
    }
}
