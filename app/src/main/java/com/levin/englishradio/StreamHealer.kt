@file:Suppress("BlockingMethodInNonBlockingContext")

package com.levin.englishradio

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray

/**
 * Finds replacement stream URLs for a station from the open Radio Browser
 * directory (community-maintained, several mirrored API servers).
 *
 * Only used as a repair path when all bundled URLs of a station keep failing.
 * All calls are blocking and must run on a worker thread.
 */
object StreamHealer {

    private val MIRRORS = listOf("de1", "nl1", "at1", "fi1")
        .map { "https://$it.api.radio-browser.info" }
    private const val USER_AGENT = "EnglishRadioAndroid/1.0"

    /** Returns a healthy URL not contained in [alreadyTried], or null. */
    fun findReplacement(station: Station, alreadyTried: Set<String>): String? {
        val query = URLEncoder.encode(station.healName, "UTF-8")
        for (base in MIRRORS) {
            val body = httpGetString("$base/json/stations/search?name=$query&hidebroken=true&limit=15")
                ?: continue
            val arr = try {
                JSONArray(body)
            } catch (e: Exception) {
                continue
            }
            if (arr.length() == 0) continue

            val candidates = (0 until arr.length())
                .map { arr.getJSONObject(it) }
                .sortedByDescending { it.optLong("votes") + it.optLong("clickcount") }

            for (c in candidates.take(12)) {
                val url = c.optString("url_resolved").ifBlank { c.optString("url") }
                if (url.isBlank() || url in alreadyTried) continue
                if (alreadyTried.any { sameStream(it, url) }) continue
                if (probeStreamUrl(url)) {
                    // Courtesy click-count so the directory keeps ranking it.
                    val uuid = c.optString("stationuuid")
                    if (uuid.isNotBlank()) httpGetString("$base/json/url/$uuid")
                    return url
                }
            }
        }
        return null
    }

    /** Two URLs are "the same stream" when host+path match (ignore query/bitrate suffixes). */
    private fun sameStream(a: String, b: String): Boolean = try {
        val ua = URL(a); val ub = URL(b)
        ua.host.equals(ub.host, ignoreCase = true) && ua.path == ub.path
    } catch (e: Exception) {
        false
    }

    private fun probeStreamUrl(url: String): Boolean {
        var conn: HttpURLConnection? = null
        val head = try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Icy-MetaData", "1")
            }
            val code = conn.responseCode
            if (code !in 200..399) return false
            val contentType = (conn.contentType ?: "").lowercase()
            try {
                conn.inputStream.use { ins ->
                    val buf = ByteArray(1024)
                    val n = ins.read(buf)
                    if (n > 0) String(buf, 0, n, Charsets.ISO_8859_1) else ""
                }
            } catch (e: Exception) {
                ""
            } to contentType
        } catch (e: Exception) {
            return false
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
        }
        val (body, contentType) = head
        val trimmed = body.trimStart()
        return when {
            trimmed.startsWith("#EXTM3U") || contentType.contains("mpegurl") ->
                probeHlsFresh(url)
            contentType.startsWith("audio/") -> true
            listOf("icy", "ogg", "aac", "mp3").any { contentType.contains(it) } -> true
            trimmed.startsWith("<html") -> false
            else -> body.isNotBlank() || contentType.isBlank()
        }
    }

    /**
     * HLS liveness check: a stream can answer HTTP 200 forever while replaying
     * a dead feed (this killed VOA Learning English). Verify the media playlist
     * carries timestamps close to now; reject stale ones.
     */
    private fun probeHlsFresh(masterUrl: String): Boolean {
        return try {
            val master = httpGetString(masterUrl) ?: return false
            val firstVariant = master.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: return true // media playlist handed to us directly
            val mediaUrl =
                if (firstVariant.startsWith("http")) firstVariant
                else URL(URL(masterUrl), firstVariant).toString()
            // The variant entry itself may be a media playlist; fetch whichever it is.
            val candidate = httpGetString(mediaUrl)
            val media = if (candidate != null && candidate.contains("#EXTINF")) candidate
            else if (candidate != null && candidate.contains("#EXTM3U")) {
                // one more level deep (rare): take its first URI line
                val deeper = candidate.lineSequence()
                    .map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                if (deeper != null) httpGetString(
                    if (deeper.startsWith("http")) deeper else URL(URL(mediaUrl), deeper).toString()
                ) else null
            } else null
            if (media == null) true else isHlsFresh(media)
        } catch (e: Exception) {
            false
        }
    }

    private fun isHlsFresh(mediaPlaylist: String): Boolean {
        val cutoff = java.time.Instant.now().minus(java.time.Duration.ofHours(48))
        // 1) PROGRAM-DATE-TIME tags (best signal)
        val pdt = mediaPlaylist.lines()
            .filter { it.startsWith("#EXT-X-PROGRAM-DATE-TIME:") }
            .lastOrNull()
            ?.substringAfter(":")?.trim()
        if (pdt != null) {
            return try {
                java.time.Instant.parse(pdt).isAfter(cutoff)
            } catch (e: Exception) {
                true
            }
        }
        // 2) timestamped segment paths like 20260824T020837/...
        val match = Regex("""(20\d{6})T(\d{6})""").findAll(mediaPlaylist).lastOrNull()
        if (match != null) {
            return try {
                val d = java.time.LocalDate.parse(
                    match.groupValues[1],
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"),
                )
                !d.isBefore(cutoff.atZone(java.time.ZoneOffset.UTC).toLocalDate())
            } catch (e: Exception) {
                true
            }
        }
        // 3) no timing info at all: accept (better than nothing)
        return true
    }

    private fun httpGetString(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 6_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
        } catch (e: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
        }
    }

    /** Cheap reachability check before switching mobile data / recovering. */
    fun anyMirrorReachable(): Boolean = MIRRORS.any {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(URL(it).host, 443), 2_000)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
