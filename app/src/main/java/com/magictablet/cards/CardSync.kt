package com.magictablet.cards

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

sealed interface SyncProgress {
    data object Connecting : SyncProgress
    data class Downloading(val which: String, val bytes: Long, val total: Long) : SyncProgress
    data class Building(val cards: Int) : SyncProgress
    data object Finalizing : SyncProgress
}

sealed interface SyncResult {
    data class Success(val cards: Int, val rulings: Int) : SyncResult
    data class Error(val message: String) : SyncResult
}

/**
 * Downloads Scryfall bulk data and rebuilds the FTS4 card DB on-device, atomically replacing
 * filesDir/cards.db. The caller must CardDb.reopen() after a Success. Runs on Dispatchers.IO.
 */
class CardSync(private val appContext: Context) {

    fun sync(onProgress: (SyncProgress) -> Unit): SyncResult {
        val oracleFile = File(appContext.cacheDir, "oracle_cards.json")
        val rulingsFile = File(appContext.cacheDir, "rulings.json")
        val newDb = File(appContext.filesDir, "${CardDb.DB_NAME}.new")
        return try {
            onProgress(SyncProgress.Connecting)
            if (!hasInternet()) return SyncResult.Error("No network connection")

            val (oracleUrl, rulingsUrl) = fetchBulkUrls()
            val oracleGz = download(oracleUrl, oracleFile) { b, t -> onProgress(SyncProgress.Downloading("cards", b, t)) }
            val rulingsGz = download(rulingsUrl, rulingsFile) { b, t -> onProgress(SyncProgress.Downloading("rulings", b, t)) }

            var built = 0
            newDb.delete()
            val counts = CardDbBuilder.build(
                newDb.path,
                forEachCard = { onCard ->
                    streamArray(oracleFile, oracleGz) { r ->
                        onCard(readRawCard(r))
                        if (++built % 2000 == 0) onProgress(SyncProgress.Building(built))
                    }
                },
                forEachRuling = { onRuling ->
                    streamArray(rulingsFile, rulingsGz) { r -> onRuling(readRawRuling(r)) }
                },
            )

            onProgress(SyncProgress.Finalizing)
            if (!validate(newDb)) {
                newDb.delete()
                return SyncResult.Error("Rebuilt database failed validation")
            }
            val target = File(appContext.filesDir, CardDb.DB_NAME)
            if (!newDb.renameTo(target)) {
                newDb.copyTo(target, overwrite = true)
                newDb.delete()
            }
            SyncResult.Success(counts.cards, counts.rulings)
        } catch (e: Exception) {
            newDb.delete()
            SyncResult.Error(e.message ?: e.toString())
        } finally {
            oracleFile.delete()
            rulingsFile.delete()
        }
    }

    private fun hasInternet(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun open(urlStr: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        return conn
    }

    /** Throw with the status + a snippet of the error body on any non-2xx response. */
    private fun HttpURLConnection.checkOk() {
        val code = responseCode
        if (code / 100 != 2) {
            val body = try {
                errorStream?.use { it.readBytes().toString(Charsets.UTF_8).take(200) }
            } catch (e: Exception) {
                null
            }
            throw java.io.IOException("HTTP $code${if (body.isNullOrBlank()) "" else ": $body"}")
        }
    }

    private fun fetchBulkUrls(): Pair<String, String> {
        val conn = open(BULK_DATA_URL)
        conn.checkOk()
        val json = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val data = JSONObject(json).getJSONArray("data")
        var oracle: String? = null
        var rulings: String? = null
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            when (item.optString("type")) {
                "oracle_cards" -> oracle = item.getString("download_uri")
                "rulings" -> rulings = item.getString("download_uri")
            }
        }
        val o = oracle ?: throw IllegalStateException("Scryfall bulk-data missing oracle_cards")
        val r = rulings ?: throw IllegalStateException("Scryfall bulk-data missing rulings")
        return o to r
    }

    /** Stream the download to [dest]; report (bytesRead, contentLength). Returns true if gzip-encoded. */
    private fun download(urlStr: String, dest: File, onBytes: (Long, Long) -> Unit): Boolean {
        val conn = open(urlStr)
        conn.setRequestProperty("Accept-Encoding", "gzip")
        conn.checkOk()
        val total = conn.contentLengthLong
        val gz = conn.getHeaderField("Content-Encoding")?.contains("gzip", ignoreCase = true) == true
        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(1 shl 16)
                var read = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    read += n
                    onBytes(read, total)
                }
            }
        }
        return gz
    }

    private fun streamArray(file: File, gz: Boolean, onElement: (JsonReader) -> Unit) {
        FileInputStream(file).use { fis ->
            val buffered = BufferedInputStream(fis)
            val stream = if (gz) GZIPInputStream(buffered) else buffered
            JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
                r.beginArray()
                while (r.hasNext()) onElement(r)
                r.endArray()
            }
        }
    }

    private fun JsonReader.nextStringOrNull(): String? =
        if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()

    private fun readRawCard(r: JsonReader): RawCard {
        var oracleId: String? = null
        var name = ""
        var layout: String? = null
        var manaCost: String? = null
        var typeLine: String? = null
        var oracleText: String? = null
        val keywords = ArrayList<String>()
        val faces = ArrayList<RawFace>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "oracle_id" -> oracleId = r.nextStringOrNull()
                "name" -> name = r.nextStringOrNull() ?: ""
                "layout" -> layout = r.nextStringOrNull()
                "mana_cost" -> manaCost = r.nextStringOrNull()
                "type_line" -> typeLine = r.nextStringOrNull()
                "oracle_text" -> oracleText = r.nextStringOrNull()
                "keywords" -> { r.beginArray(); while (r.hasNext()) keywords.add(r.nextString()); r.endArray() }
                "card_faces" -> { r.beginArray(); while (r.hasNext()) faces.add(readRawFace(r)); r.endArray() }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return RawCard(oracleId, name, layout, manaCost, typeLine, oracleText, keywords, faces)
    }

    private fun readRawFace(r: JsonReader): RawFace {
        var manaCost: String? = null
        var typeLine: String? = null
        var oracleText: String? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "mana_cost" -> manaCost = r.nextStringOrNull()
                "type_line" -> typeLine = r.nextStringOrNull()
                "oracle_text" -> oracleText = r.nextStringOrNull()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return RawFace(manaCost, typeLine, oracleText)
    }

    private fun readRawRuling(r: JsonReader): RawRuling {
        var oracleId: String? = null
        var publishedAt: String? = null
        var comment: String? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "oracle_id" -> oracleId = r.nextStringOrNull()
                "published_at" -> publishedAt = r.nextStringOrNull()
                "comment" -> comment = r.nextStringOrNull()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return RawRuling(oracleId, publishedAt, comment)
    }

    private fun validate(dbFile: File): Boolean = try {
        android.database.sqlite.SQLiteDatabase
            .openDatabase(dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
            .use { conn ->
                conn.rawQuery("SELECT count(*) FROM Card", null).use { c ->
                    c.moveToFirst() && c.getLong(0) > 0
                }
            }
    } catch (e: Exception) {
        false
    }

    companion object {
        private const val BULK_DATA_URL = "https://api.scryfall.com/bulk-data"
        private const val USER_AGENT = "MagicTablet/0.1 (personal fan project)"
    }
}
