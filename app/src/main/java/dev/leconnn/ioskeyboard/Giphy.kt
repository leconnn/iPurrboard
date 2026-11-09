package dev.leconnn.ioskeyboard

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GIF search through GIPHY's API (Google shut Tenor's API down in June 2026). The API key comes
 * from local.properties (`giphy.apiKey`, never committed) through BuildConfig. All calls block:
 * run them off the main thread.
 *
 * A free key allows 100 API calls an hour, shared by everyone using the build, so results are
 * cached in memory and on disk: trending for [TRENDING_TTL_MS], each search for
 * [SEARCH_TTL_MS]. When the network fails, older cached results are shown rather than nothing.
 * (The GIF images themselves come from GIPHY's media servers, not the API.)
 */
object Giphy {

    /** One GIF: a small preview for the panel and a larger version to send. */
    data class Gif(
        val id: String,
        val title: String,
        val previewUrl: String,
        val previewWidth: Int,
        val previewHeight: Int,
        val sendUrl: String,
    )

    val configured get() = BuildConfig.GIPHY_API_KEY.isNotBlank()

    fun trending(context: Context): List<Gif> =
        cached(context, "trending", TRENDING_TTL_MS) { fetchBody("trending", "limit=$LIMIT") }

    fun search(context: Context, query: String): List<Gif> {
        val q = query.trim().lowercase()
        return cached(context, "search:$q", SEARCH_TTL_MS) {
            fetchBody("search", "q=${URLEncoder.encode(q, "UTF-8")}&limit=$LIMIT&lang=en")
        }
    }

    /** Recent responses: key -> (when fetched, JSON body). */
    private val memory = object : LinkedHashMap<String, Pair<Long, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, String>>?) = size > MAX_CACHED
    }

    /**
     * The response for [key]: from memory or disk while younger than [ttl], otherwise from the
     * network (and stored). If the network fails, any older copy is used instead.
     */
    private fun cached(context: Context, key: String, ttl: Long, load: () -> String): List<Gif> {
        if (!configured) return emptyList()
        val now = System.currentTimeMillis()
        val file = File(cacheDir(context), sha1(key) + ".json")
        val known = synchronized(memory) { memory[key] }
            ?: file.takeIf { it.isFile }?.let { f -> runCatching { f.lastModified() to f.readText() }.getOrNull() }
        if (known != null && now - known.first < ttl) {
            synchronized(memory) { memory[key] = known }
            return parse(known.second)
        }
        val body = try {
            load()
        } catch (e: java.io.IOException) {
            // Offline or over the hourly limit: older results beat an error.
            return known?.let { parse(it.second) } ?: throw e
        }
        synchronized(memory) { memory[key] = now to body }
        runCatching { file.writeText(body) }
        pruneDisk(context)
        return parse(body)
    }

    private fun cacheDir(context: Context) = File(context.cacheDir, "giphy").apply { mkdirs() }

    /** Keeps the newest [MAX_CACHED] responses on disk. */
    private fun pruneDisk(context: Context) {
        val files = cacheDir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_CACHED).forEach { it.delete() }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** The bytes at [url], or null on failure. */
    fun download(url: String, maxBytes: Int = 8 * 1024 * 1024): ByteArray? = runCatching {
        val c = open(url)
        try {
            if (c.responseCode != 200) return null
            c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) return null
                }
                out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    /** One API call: the raw JSON response. */
    private fun fetchBody(endpoint: String, params: String): String {
        val url = "https://api.giphy.com/v1/gifs/$endpoint?api_key=${BuildConfig.GIPHY_API_KEY}&$params" +
            "&rating=pg-13&bundle=messaging_non_clips"
        val c = open(url)
        try {
            if (c.responseCode != 200) throw java.io.IOException("GIPHY ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun parse(body: String): List<Gif> {
        return runCatching {
            val data = JSONObject(body).getJSONArray("data")
            return (0 until data.length()).mapNotNull { i ->
                val g = data.getJSONObject(i)
                val images = g.optJSONObject("images") ?: return@mapNotNull null
                val preview = images.optJSONObject("fixed_width_small") ?: images.optJSONObject("fixed_width") ?: return@mapNotNull null
                val send = images.optJSONObject("downsized") ?: images.optJSONObject("fixed_width") ?: preview
                Gif(
                    id = g.optString("id"),
                    title = g.optString("title"),
                    previewUrl = preview.optString("url"),
                    previewWidth = preview.optString("width").toIntOrNull() ?: 100,
                    previewHeight = preview.optString("height").toIntOrNull() ?: 100,
                    sendUrl = send.optString("url"),
                ).takeIf { it.previewUrl.isNotEmpty() && it.sendUrl.isNotEmpty() }
            }
        }.getOrDefault(emptyList())
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 10000
        setRequestProperty("User-Agent", "iPurrboard")
    }

    private const val LIMIT = 40
    private const val TRENDING_TTL_MS = 30 * 60 * 1000L
    private const val SEARCH_TTL_MS = 60 * 60 * 1000L
    private const val MAX_CACHED = 40
}
