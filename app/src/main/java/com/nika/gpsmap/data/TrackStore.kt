package com.nika.gpsmap.data

import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import androidx.core.content.FileProvider
import com.nika.gpsmap.Fmt
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class GpxPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val timeMillis: Long
)

data class TrackSummary(
    val file: File,
    val name: String,
    val startedAt: Long,
    val durationMillis: Long,
    val distanceMeters: Double,
    val pointCount: Int
)

object TrackStore {

    private const val TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss"
    private const val LEGACY_NAME = "GPS Map Pro track"
    const val MAX_NAME_LENGTH = 60

    fun directory(context: Context): File {
        val dir = File(context.filesDir, "tracks")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun save(context: Context, points: List<TrackPoint>, startedAt: Long = System.currentTimeMillis()): File {
        require(points.isNotEmpty()) { "No points to save" }
        val dir = directory(context)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAt))
        var file = File(dir, "track_$stamp.gpx")
        var suffix = 1
        while (file.exists()) {
            file = File(dir, "track_${stamp}_$suffix.gpx")
            suffix++
        }

        val iso = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val trackName = escapeXml(Fmt.dateTime(startedAt))
        file.bufferedWriter().use { writer ->
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            writer.write("<gpx version=\"1.1\" creator=\"GPS Map Pro\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            writer.write("  <metadata><name>$trackName</name><time>")
            writer.write(iso.format(Date(startedAt)))
            writer.write("Z</time></metadata>\n")
            writer.write("  <trk><name>$trackName</name><trkseg>\n")
            points.forEach { point ->
                writer.write("    <trkpt lat=\"")
                writer.write(point.latitude.toString())
                writer.write("\" lon=\"")
                writer.write(point.longitude.toString())
                writer.write("\"><ele>")
                writer.write(point.altitude.toString())
                writer.write("</ele><time>")
                writer.write(iso.format(Date(point.timeMillis)))
                writer.write("Z</time></trkpt>\n")
            }
            writer.write("  </trkseg></trk>\n")
            writer.write("</gpx>\n")
        }
        return file
    }

    fun list(context: Context): List<TrackSummary> {
        val files = directory(context).listFiles { candidate ->
            candidate.isFile && candidate.extension.equals("gpx", ignoreCase = true)
        } ?: return emptyList()

        return files.mapNotNull { file ->
            runCatching {
                val parsed = parseTrack(file)
                val points = parsed.points
                if (points.isEmpty()) return@runCatching null
                var distance = 0.0
                val result = FloatArray(1)
                for (index in 1 until points.size) {
                    Location.distanceBetween(
                        points[index - 1].latitude,
                        points[index - 1].longitude,
                        points[index].latitude,
                        points[index].longitude,
                        result
                    )
                    distance += result[0]
                }
                val started = points.first().timeMillis
                val duration = (points.last().timeMillis - points.first().timeMillis).coerceAtLeast(0L)
                TrackSummary(
                    file = file,
                    name = parsed.name
                        ?.takeIf { it.isNotBlank() && it != LEGACY_NAME }
                        ?: Fmt.dateTime(started),
                    startedAt = started,
                    durationMillis = duration,
                    distanceMeters = distance,
                    pointCount = points.size
                )
            }.getOrNull()
        }.sortedByDescending { it.startedAt }
    }

    fun load(file: File): List<GpxPoint> = parse(file)

    /**
     * Renames a track in place by rewriting the GPX <name> tags. The file name
     * stays the same so the displayed/route track references remain valid.
     */
    fun rename(file: File, newName: String): Boolean {
        val cleaned = newName.trim().take(MAX_NAME_LENGTH)
        if (cleaned.isEmpty()) return false
        return runCatching {
            val text = file.readText()
            val replacement = "<name>${escapeXml(cleaned)}</name>"
            val updated = if (NAME_TAG.containsMatchIn(text)) {
                text.replace(NAME_TAG) { replacement }
            } else {
                insertName(text, replacement)
            }
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(updated)
            if (parseTrack(temp).points.isEmpty()) {
                temp.delete()
                return@runCatching false
            }
            if (!temp.renameTo(file)) {
                file.writeText(updated)
                temp.delete()
            }
            true
        }.getOrDefault(false)
    }

    fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    fun shareIntent(context: Context, file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(com.nika.gpsmap.R.string.track_share_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun parse(file: File): List<GpxPoint> = parseTrack(file).points

    private fun parseTrack(file: File): ParsedTrack {
        val text = runCatching { file.readText() }.getOrElse {
            return ParsedTrack(emptyList(), null)
        }
        return parseXml(text)
    }

    private fun parseXml(text: String): ParsedTrack {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(text))

        val points = ArrayList<GpxPoint>()
        var trackName: String? = null
        var lat = Double.NaN
        var lon = Double.NaN
        var altitude = 0.0
        var time = 0L
        var insidePoint = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "name" -> if (!insidePoint && trackName == null) {
                            trackName = parser.nextText().trim()
                        }

                        "trkpt" -> {
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: Double.NaN
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: Double.NaN
                            altitude = 0.0
                            time = 0L
                            insidePoint = true
                        }

                        "ele" -> if (insidePoint) {
                            altitude = parser.nextText().trim().toDoubleOrNull() ?: 0.0
                        }

                        "time" -> if (insidePoint) {
                            val value = parser.nextText().trim()
                            time = parseTime(value)
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "trkpt") {
                        if (!lat.isNaN() && !lon.isNaN()) {
                            points.add(
                                GpxPoint(
                                    latitude = lat,
                                    longitude = lon,
                                    altitude = altitude,
                                    timeMillis = if (time > 0L) time else System.currentTimeMillis()
                                )
                            )
                        }
                        insidePoint = false
                    }
                }
            }
            event = parser.next()
        }
        points.sortBy { it.timeMillis }
        return ParsedTrack(points, trackName)
    }

    private fun insertName(text: String, replacement: String): String {
        val metadata = METADATA_TAG.find(text)
        if (metadata != null) {
            val insertAt = metadata.range.last + 1
            return text.substring(0, insertAt) + replacement + text.substring(insertAt)
        }
        val trk = TRK_TAG.find(text) ?: return text
        return text.substring(0, trk.range.first) +
            "<metadata>$replacement</metadata>\n  " +
            text.substring(trk.range.first)
    }

    private fun escapeXml(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(character)
            }
        }
    }

    private data class ParsedTrack(val points: List<GpxPoint>, val name: String?)

    private val NAME_TAG = Regex("<name>.*?</name>", RegexOption.DOT_MATCHES_ALL)
    private val METADATA_TAG = Regex("<metadata(\\s[^>]*)?>")
    private val TRK_TAG = Regex("<trk(\\s[^>]*)?>")

    private fun parseTime(value: String): Long {
        if (value.isEmpty()) return 0L
        val cleaned = value.removeSuffix("Z")
        val patterns = listOf(
            TIMESTAMP_PATTERN,
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss.S"
        )
        for (pattern in patterns) {
            runCatching {
                val format = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                return format.parse(cleaned)?.time ?: 0L
            }
        }
        return 0L
    }
}
