package com.fleetracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.URL
import java.util.Locale

/**
 * GPS noktalarını OSRM "Match" servisi ile gerçek yollara oturtur.
 * Servise ulaşılamazsa orijinal (ham) noktaları döndürür.
 */
object OsrmSnapper {

    private const val MAX_COORDS = 100

    suspend fun snap(points: List<GeoPoint>): List<GeoPoint> = withContext(Dispatchers.IO) {
        if (points.size < 2) return@withContext points
        val sampled = sample(points)
        val coords = sampled.joinToString(";") {
            String.format(Locale.US, "%.6f,%.6f", it.longitude, it.latitude)
        }
        val url =
            "https://router.project-osrm.org/match/v1/driving/$coords?overview=full&geometries=polyline"
        try {
            val root = JSONObject(URL(url).readText())
            if (root.optString("code") != "Ok") return@withContext points
            val geometry = root.getJSONArray("matchings").getJSONObject(0).getString("geometry")
            val snapped = decodePolyline(geometry)
            if (snapped.size >= 2) snapped else points
        } catch (e: Exception) {
            // İnternet yok / sunucu yanıt vermiyor → ham iz
            points
        }
    }

    /** OSRM demo sunucusu için nokta sayısını en fazla 100'e düşürür. */
    private fun sample(points: List<GeoPoint>): List<GeoPoint> {
        if (points.size <= MAX_COORDS) return points
        return (0 until MAX_COORDS).map { i ->
            points[i * (points.size - 1) / (MAX_COORDS - 1)]
        }.distinct()
    }

    /** OSRM polyline (encoded) geometrisini çözer. */
    private fun decodePolyline(encoded: String): List<GeoPoint> {
        val result = mutableListOf<GeoPoint>()
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            var shift = 0
            var value = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                value = value or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dLat = if (value and 1 != 0) (value shr 1).inv() else value shr 1
            lat += dLat

            shift = 0
            value = 0
            do {
                b = encoded[index++].code - 63
                value = value or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dLon = if (value and 1 != 0) (value shr 1).inv() else value shr 1
            lon += dLon

            result.add(GeoPoint(lat / 1e5, lon / 1e5))
        }
        return result
    }
}

