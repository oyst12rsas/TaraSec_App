package org.tarasec.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class NearbyChallenge(
    val id: Int,
    val slug: String,
    val title: String,
    val joinText: String,
    val description: String,
    val destination: String,
    val destinationUrl: String
)

object NearbyChallengeClient {
    private const val ENDPOINT = "https://tarasec.org/script/appChallenges.php"

    fun current(): NearbyChallenge? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(ENDPOINT).openConnection() as HttpURLConnection
            connection.connectTimeout = 3500
            connection.readTimeout = 5000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) return null
            val items = json.optJSONArray("challenges") ?: return null
            if (items.length() == 0) return null
            val item = items.optJSONObject(0) ?: return null
            NearbyChallenge(
                id = item.optInt("id"),
                slug = item.optString("slug"),
                title = item.optString("title", "Nearby TaraSec challenge"),
                joinText = item.optString("join_text", "Join challenge"),
                description = item.optString("description"),
                destination = item.optString("destination", "demo3"),
                destinationUrl = item.optString("destination_url")
            )
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
