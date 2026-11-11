package dev.leconnn.ioskeyboard

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Software Update, as on iOS: finds a newer iPurrboard on the build's own GitHub repository
 * ([BuildConfig.UPDATE_REPO]; the personal build reads its private repository with a read-only
 * token), downloads its APK and hands it to Android's package installer, which asks the user to
 * confirm. Checks run at most once a day while Automatic Checks is on (Settings › Software
 * Update), or when asked. Nothing about the user is sent: GitHub sees an ordinary request.
 */
object Updater {

    /** The newest release found. [apiUrl] downloads the APK (with the token, for a private repo). */
    data class Release(
        val version: String, val notes: String, val apkName: String, val size: Long,
        val downloadUrl: String, val apiUrl: String, val page: String,
    )

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Can this build check at all? (The personal build needs its token.) */
    val available get() = BuildConfig.UPDATE_REPO.isNotEmpty() && (!BuildConfig.UPDATE_PRIVATE || BuildConfig.UPDATE_TOKEN.isNotEmpty())

    /** The release saved by the last check, if it's newer than this build. */
    fun pending(context: Context): Release? {
        val p = Prefs.get(context).raw
        val v = p.getString(KEY_VERSION, null) ?: return null
        if (compare(v, BuildConfig.VERSION_NAME) <= 0) return null
        return Release(v, p.getString(KEY_NOTES, "").orEmpty(), p.getString(KEY_APK, "").orEmpty(), p.getLong(KEY_SIZE, 0L),
            p.getString(KEY_URL, "").orEmpty(), p.getString(KEY_API, "").orEmpty(), p.getString(KEY_PAGE, "").orEmpty())
    }

    /** When the last check finished (0: never). */
    fun lastChecked(context: Context) = Prefs.get(context).raw.getLong(KEY_CHECKED, 0L)

    /** Checks if Automatic Checks is on and the last check is over a day old. */
    fun checkIfDue(context: Context, done: (() -> Unit)? = null) {
        val prefs = Prefs.get(context)
        if (!available || !prefs.autoUpdateCheck) return
        if (System.currentTimeMillis() - lastChecked(context) < DAY_MS) return
        check(context) { done?.invoke() }
    }

    /** Asks GitHub for the newest release now; [done] runs on the main thread (null result: failed). */
    fun check(context: Context, done: (Result<Release?>) -> Unit) {
        val app = context.applicationContext
        io.execute {
            val r = runCatching { fetchNewest() }
            r.getOrNull()?.let { save(app, it) }
            if (r.isSuccess) Prefs.get(app).raw.edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
            main.post { done(r.map { pending(app) }) }
        }
    }

    private fun fetchNewest(): Release? {
        val c = open("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=10", "application/vnd.github+json")
        val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val list = JSONArray(body)
        var best: Release? = null
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            if (r.optBoolean("draft")) continue
            val version = r.optString("tag_name").removePrefix("v").removePrefix("V")
            if (version.isEmpty()) continue
            val assets = r.optJSONArray("assets") ?: continue
            // This build's APK: the one named for its flavour (personal / public), else any APK.
            var apk: org.json.JSONObject? = null
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                val name = a.optString("name")
                if (!name.endsWith(".apk")) continue
                if (BuildConfig.FLAVOR.isNotEmpty() && name.contains(BuildConfig.FLAVOR)) { apk = a; break }
                if (apk == null) apk = a
            }
            apk ?: continue
            if (best == null || compare(version, best.version) > 0) {
                best = Release(version, r.optString("body"), apk.optString("name"), apk.optLong("size"),
                    apk.optString("browser_download_url"), apk.optString("url"), r.optString("html_url"))
            }
        }
        return best
    }

    private fun save(context: Context, r: Release) {
        Prefs.get(context).raw.edit()
            .putString(KEY_VERSION, r.version).putString(KEY_NOTES, r.notes).putString(KEY_APK, r.apkName)
            .putLong(KEY_SIZE, r.size).putString(KEY_URL, r.downloadUrl).putString(KEY_API, r.apiUrl)
            .putString(KEY_PAGE, r.page).apply()
    }

    /**
     * Downloads [r]'s APK into the app's cache, reporting progress (0..1) on the main thread, then
     * [done] with the file (or the error).
     */
    fun download(context: Context, r: Release, progress: (Float) -> Unit, done: (Result<File>) -> Unit) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        io.execute {
            val result = runCatching {
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "iPurrboard-${r.version}.apk")
                // Public: the plain download link. Private: the API link with the token, which
                // redirects to a signed link that must be fetched without the token.
                var c = if (BuildConfig.UPDATE_PRIVATE) open(r.apiUrl, "application/octet-stream", follow = false)
                    else open(r.downloadUrl, "application/octet-stream")
                if (c.responseCode in 300..399) {
                    val next = c.getHeaderField("Location") ?: error("No download link")
                    c.disconnect()
                    c = (URL(next).openConnection() as HttpURLConnection).apply {
                        connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS
                        setRequestProperty("User-Agent", USER_AGENT)
                    }
                }
                if (c.responseCode !in 200..299) error("Download failed (${c.responseCode})")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
                var read = 0L
                var lastReport = 0L
                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            val now = System.currentTimeMillis()
                            if (total > 0 && now - lastReport > 80) { lastReport = now; main.post { progress(read.toFloat() / total) } }
                        }
                    }
                }
                file
            }
            main.post { done(result) }
        }
    }

    /** Hands [apk] to Android's installer, which shows its own confirmation. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("iPurrboard.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val intent = Intent(context, InstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    /** May this app install updates? (Android asks once: Settings › Install unknown apps.) */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Version strings compared number by number ("0.10" > "0.9"; "0.2" > "0.1-beta"). */
    fun compare(a: String, b: String): Int {
        val x = Regex("\\d+").findAll(a).map { it.value.toInt() }.toList()
        val y = Regex("\\d+").findAll(b).map { it.value.toInt() }.toList()
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    private fun open(url: String, accept: String, follow: Boolean = true): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = follow
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (BuildConfig.UPDATE_TOKEN.isNotEmpty()) setRequestProperty("Authorization", "Bearer ${BuildConfig.UPDATE_TOKEN}")
        }.also { if (it.responseCode == 401 || it.responseCode == 404) error("Can't reach updates (${it.responseCode})") }

    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 20_000
    private const val USER_AGENT = "iPurrboard-updater"
    private const val KEY_VERSION = "update.version"
    private const val KEY_NOTES = "update.notes"
    private const val KEY_APK = "update.apk"
    private const val KEY_SIZE = "update.size"
    private const val KEY_URL = "update.url"
    private const val KEY_API = "update.api"
    private const val KEY_PAGE = "update.page"
    private const val KEY_CHECKED = "update.checkedAt"
}

/** Android's installer reports here: when it needs the user's confirmation, show its screen. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The update couldn't be installed."
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
