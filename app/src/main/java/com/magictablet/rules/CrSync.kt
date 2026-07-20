package com.magictablet.rules

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

sealed interface CrSyncProgress {
    data object Connecting : CrSyncProgress
    data class Downloading(val bytes: Long, val total: Long) : CrSyncProgress
    data class Parsing(val rules: Int) : CrSyncProgress
    data object Finalizing : CrSyncProgress
}

sealed interface CrSyncResult {
    data class Success(val rules: Int, val terms: Int) : CrSyncResult
    data class Error(val message: String) : CrSyncResult
}

/** Downloads the CR text from [url], parses it, and rebuilds filesDir/cr.db (atomic swap). Runs on IO. */
class CrSync(private val appContext: Context) {

    fun sync(url: String, onProgress: (CrSyncProgress) -> Unit): CrSyncResult {
        val temp = File(appContext.cacheDir, "comp_rules.txt")
        val newDb = File(appContext.filesDir, "${CrDb.DB_NAME}.new")
        return try {
            onProgress(CrSyncProgress.Connecting)
            if (!hasInternet()) return CrSyncResult.Error("No network connection")

            val gz = download(url, temp) { b, t -> onProgress(CrSyncProgress.Downloading(b, t)) }

            onProgress(CrSyncProgress.Parsing(0))
            val corpus = reader(temp, gz).use { r -> parseCr(r.lineSequence()) }
            onProgress(CrSyncProgress.Parsing(corpus.rules.size))
            if (corpus.rules.isEmpty()) return CrSyncResult.Error("No rules found — check the URL")

            newDb.delete()
            CrDbBuilder.build(newDb.path, corpus.rules, corpus.glossary)
            onProgress(CrSyncProgress.Finalizing)
            if (!validate(newDb)) { newDb.delete(); return CrSyncResult.Error("Rebuilt rules failed validation") }

            val target = File(appContext.filesDir, CrDb.DB_NAME)
            if (!newDb.renameTo(target)) { newDb.copyTo(target, overwrite = true); newDb.delete() }
            CrSyncResult.Success(corpus.rules.size, corpus.glossary.size)
        } catch (e: Exception) {
            newDb.delete()
            CrSyncResult.Error(e.message ?: e.toString())
        } finally {
            temp.delete()
        }
    }

    private fun hasInternet(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun download(urlStr: String, dest: File, onBytes: (Long, Long) -> Unit): Boolean {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "MagicTablet/0.1 (personal fan project)")
        conn.setRequestProperty("Accept-Encoding", "gzip")
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
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

    private fun reader(file: File, gz: Boolean): BufferedReader {
        val ins = FileInputStream(file)
        return try {
            val stream = if (gz) GZIPInputStream(ins) else ins
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        } catch (e: Throwable) {
            ins.close()
            throw e
        }
    }

    private fun validate(dbFile: File): Boolean = try {
        android.database.sqlite.SQLiteDatabase
            .openDatabase(dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
            .use { conn ->
                conn.rawQuery("SELECT count(*) FROM Rule", null).use { it.moveToFirst() && it.getLong(0) > 0 }
            }
    } catch (e: Exception) {
        false
    }
}
