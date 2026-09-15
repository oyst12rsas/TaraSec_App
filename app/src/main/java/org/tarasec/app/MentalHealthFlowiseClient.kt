package org.tarasec.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MentalHealthReply(val text: String, val chatId: String?)

object MentalHealthFlowiseClient {
    val isConfigured: Boolean
        get() {
            val flowiseUrl = BuildConfig.MENTAL_HEALTH_FLOWISE_URL
            val flowiseApiKey = BuildConfig.MENTAL_HEALTH_FLOWISE_API_KEY
            return flowiseUrl.isNotBlank() &&
                !flowiseUrl.contains("YOUR-FLOWISE-HOST") &&
                !flowiseUrl.contains("YOUR-CHATFLOW-ID") &&
                flowiseApiKey.isNotBlank() &&
                flowiseApiKey != "DUMMY_REPLACE_ME"
        }

    fun send(message: String, chatId: String?): MentalHealthReply {
        val flowiseUrl = BuildConfig.MENTAL_HEALTH_FLOWISE_URL
        val flowiseApiKey = BuildConfig.MENTAL_HEALTH_FLOWISE_API_KEY

        require(isConfigured) {
            "Mental-health Flowise endpoint and API key are not configured in TaraSec yet."
        }

        val payload = JSONObject().apply {
            put("question", message)
            if (!chatId.isNullOrBlank()) {
                put("overrideConfig", JSONObject().put("sessionId", chatId))
            }
        }

        val connection = URL(flowiseUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 90_000
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            if (flowiseApiKey.isNotBlank() && flowiseApiKey != "DUMMY_REPLACE_ME") {
                connection.setRequestProperty("Authorization", "Bearer $flowiseApiKey")
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
