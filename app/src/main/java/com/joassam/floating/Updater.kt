package com.joassam.floating

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 스스로 업데이트: 깃허브 Releases에 더 높은 빌드가 올라오면 받아 두었다가 설치한다.
 * (설치 자체는 안드로이드 규칙상 처음 한 번은 "설치" 확인이 필요하고, 그 뒤로는 조용히 업데이트되는 폰이 많다)
 */
object Updater {
    // 플로팅 앱 저장소 (공개 저장소여야 휴대폰이 읽을 수 있어요)
    const val REPO = "jojojojojo2243-del/joassam-floating"
    private const val PREFS = "joassam_updater"

    fun currentCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun apk(ctx: Context) = File(ctx.cacheDir, "update.apk")

    /** 받아 둔 새 버전 번호 (없으면 0) */
    fun readyCode(ctx: Context): Long {
        val c = prefs(ctx).getLong("ready_code", 0)
        return if (c > currentCode(ctx) && apk(ctx).exists() && apk(ctx).length() > 100000) c else 0
    }

    /** 새 버전이 있으면 내려받아 둔다. 결과 문장을 onDone으로 돌려준다. (백그라운드 스레드에서 실행) */
    fun checkAndDownload(ctx: Context, force: Boolean, onDone: ((String) -> Unit)? = null) {
        val app = ctx.applicationContext
        val p = prefs(app)
        if (!force && System.currentTimeMillis() - p.getLong("last_check", 0) < 60 * 60 * 1000) { onDone?.invoke(""); return }
        Thread {
            var msg = ""
            try {
                p.edit().putLong("last_check", System.currentTimeMillis()).apply()
                val json = JSONObject(httpGet("https://api.github.com/repos/$REPO/releases/latest"))
                val tag = json.optString("tag_name", "")
                val latest = Regex("(\\d+)$").find(tag)?.value?.toLongOrNull() ?: 0L
                var url = ""
                val assets = json.optJSONArray("assets")
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
                }
                val mine = currentCode(app)
                if (latest <= mine || url.isEmpty()) {
                    msg = "최신 버전이에요 (빌드 $mine)"
                    apk(app).delete(); p.edit().remove("ready_code").apply()
                } else if (readyCode(app) == latest) {
                    msg = "새 버전(빌드 $latest)을 받아 두었어요"
                } else {
                    val tmp = File(app.cacheDir, "update.part")
                    download(url, tmp)
                    apk(app).delete()
                    tmp.renameTo(apk(app))
                    p.edit().putLong("ready_code", latest).apply()
                    msg = "새 버전(빌드 $latest)을 받았어요"
                }
            } catch (e: Exception) {
                msg = "업데이트 확인 실패: " + (e.message ?: "인터넷을 확인해 주세요")
            }
            onDone?.invoke(msg)
        }.start()
    }

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** 받아 둔 새 버전이 있으면 설치를 시작한다. 시작했으면 true */
    fun installIfReady(ctx: Context): Boolean {
        val app = ctx.applicationContext
        if (readyCode(app) == 0L || !canInstall(app)) return false
        return try {
            val pi = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                apk(app).inputStream().use { input ->
                    s.openWrite("update", 0, apk(app).length()).use { out ->
                        input.copyTo(out); s.fsync(out)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(app, id, Intent(app, UpdateReceiver::class.java), flags)
                s.commit(pending.intentSender)
            }
            true
        } catch (e: Exception) { false }
    }

    private fun httpGet(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "joassam-floating")
        if (c.responseCode != 200) throw Exception("서버 응답 " + c.responseCode)
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun download(u: String, to: File) {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 60000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "joassam-floating")
        if (c.responseCode != 200) throw Exception("내려받기 응답 " + c.responseCode)
        c.inputStream.use { i -> to.outputStream().use { o -> i.copyTo(o) } }
    }
}

/** 설치 진행 상태를 받는다. 확인 창이 필요하면 띄운다. */
class UpdateReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { ctx.startActivity(confirm) } catch (_: Exception) {}
            }
        }
    }
}
