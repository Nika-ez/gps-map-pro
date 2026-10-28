package com.nika.gpsmap.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class PlaceResult(
    val displayName: String,
    val latitude: Double,
    val longitude: Double
)

object NominatimClient {

    private const val BASE = "https://nominatim.openstreetmap.org"
    private const val USER_AGENT = "GPSMapPro/2.0 (com.nika.gpsmap; Android)"

    suspend fun search(query: String): List<PlaceResult> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = request("$BASE/search?format=jsonv2&addressdetails=1&limit=8&q=$encoded")
        val array = JSONArray(body)
        val results = ArrayList<PlaceResult>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("display_name")
            val lat = item.optString("lat").toDoubleOrNull() ?: continue
            val lon = item.optString("lon").toDoubleOrNull() ?: continue
            if (name.isNotBlank()) {
                results.add(PlaceResult(name, lat, lon))
            }
        }
        results
    }

    suspend fun reverse(latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
        val body = request(
            "$BASE/reverse?format=jsonv2&zoom=18&addressdetails=1" +
                "&lat=$latitude&lon=$longitude"
        )
        val json = org.json.JSONObject(body)
        json.optString("display_name").takeIf { it.isNotBlank() }
    }

    private fun request(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", "en")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("Nominatim HTTP $code")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
