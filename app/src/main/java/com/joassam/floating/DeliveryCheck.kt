package com.joassam.floating

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 배송 알림 (앱을 닫아 둬도 정해진 시간에):
 *  - 발송일 D → 다음 배달일(일요일 건너뜀) 12시·16시: 배송중 / 배송완료 구분 알림
 *  - 그다음 날 9시: 아직 도착 안 된 것만 경고 (일요일이어도 알림)
 * 송장 목록은 주문관리 앱이 저장해 둔 작은 문서(stores/_track)에서 읽고, 상태는 주문관리 앱의 /api/epost(우체국 조회)로 확인한다.
 */
object DeliveryCheck {
    const val ACTION = "com.joassam.floating.DELIVERY_CHECK"
    private const val PREFS = "joassam_delivery"
    private const val CHANNEL = "delivery"
    private const val API_KEY = "AIzaSyBx1jLv2JoD2-MjXlkgoQNj6U1iCle9Hh0"
    private const val PROJECT = "joassam9288"
    private const val TRACK_DOC = "_track"
    private val HOURS = intArrayOf(9, 15)  // v2.0: 다음날 15시 = 배송중만, 그다음 날 9시 = 미도착만
    // 서버 주소 (시험할 때만 바꿈)
    var authBase = "https://identitytoolkit.googleapis.com"
    var tokenBase = "https://securetoken.googleapis.com"
    var firestoreBase = "https://firestore.googleapis.com"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- 알람 예약 ----------
    /** 다음 알림 시각(9·12·16시 중 지금 이후 가장 가까운 것) */
    fun nextSlot(nowMs: Long): Calendar {
        val c = Calendar.getInstance()
        c.timeInMillis = nowMs
        for (dayAdd in 0..1) {
            for (h in HOURS) {
                val t = Calendar.getInstance()
                t.timeInMillis = nowMs
                t.add(Calendar.DAY_OF_MONTH, dayAdd)
                t.set(Calendar.HOUR_OF_DAY, h)
                t.set(Calendar.MINUTE, 0)
                t.set(Calendar.SECOND, 5)
                t.set(Calendar.MILLISECOND, 0)
                if (t.timeInMillis > nowMs + 20000) return t
            }
        }
        c.add(Calendar.HOUR_OF_DAY, 1)
        return c
    }

    fun schedule(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next = nextSlot(System.currentTimeMillis())
        val i = Intent(app, DeliveryReceiver::class.java)
        i.action = ACTION
        i.putExtra("hour", next.get(Calendar.HOUR_OF_DAY))
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(app, 2601, i, flags)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi)
        }
        prefs(app).edit().putLong("next_at", next.timeInMillis).apply()
    }

    /** v1.8: 우체국 조회가 안 됐을 때 5분 뒤 한 번 더 (같은 시각 알림으로) */
    fun scheduleRetry(ctx: Context, hour: Int, delayMs: Long = 5 * 60 * 1000L) {
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val i = Intent(app, DeliveryReceiver::class.java)
        i.action = ACTION
        i.putExtra("hour", hour)
        i.putExtra("retry", true)
        val pi = PendingIntent.getBroadcast(app, 2605, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val at = System.currentTimeMillis() + delayMs
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    fun nextText(ctx: Context): String {
        val t = prefs(ctx).getLong("next_at", 0)
        if (t <= 0) return "예약 안 됨"
        val c = Calendar.getInstance()
        c.timeInMillis = t
        return "${c.get(Calendar.MONTH) + 1}/${c.get(Calendar.DAY_OF_MONTH)} ${c.get(Calendar.HOUR_OF_DAY)}시"
    }

    fun lastText(ctx: Context): String = prefs(ctx).getString("last_result", "") ?: ""

    // ---------- 시간표 (주문관리 앱과 같은 규칙) ----------
    private fun ymd(c: Calendar): String =
        String.format("%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

    private fun parseDay(s: String): Calendar? {
        val m = Regex("(\\d{4})-(\\d{2})-(\\d{2})").find(s) ?: return null
        val c = Calendar.getInstance()
        c.clear()
        c.set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt(), 0, 0, 0)
        return c
    }

    private fun nextDeliveryDay(d: Calendar): Calendar {
        val x = d.clone() as Calendar
        x.add(Calendar.DAY_OF_MONTH, 1)
        if (x.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) x.add(Calendar.DAY_OF_MONTH, 1)
        return x
    }

    /** 이 발송일이 오늘 이 시각에 알릴 대상인지: "status"(15시, 배송중만) / "late"(9시) / null */
    fun kindFor(shipDay: String, today: Calendar, hour: Int): String? {
        val d = parseDay(shipDay) ?: return null
        val a = nextDeliveryDay(d)
        val n = a.clone() as Calendar
        n.add(Calendar.DAY_OF_MONTH, 1)
        val t = ymd(today)
        if (hour == 15 && ymd(a) == t) return "status"
        if (hour == 9 && ymd(n) == t) return "late"
        return null
    }

    // ---------- 실행 ----------
    data class Item(val no: String, val name: String, val day: String)
    data class Status(val state: String, val last: String)

    /** 알람에서 호출 (백그라운드 스레드). manual=true면 시각과 상관없이 최근 3일 발송분 현황을 알림 */
    fun run(ctx: Context, hour: Int, manual: Boolean, nowMs: Long = System.currentTimeMillis()): String {
        val app = ctx.applicationContext
        val today = Calendar.getInstance()
        today.timeInMillis = nowMs
        val key = ymd(today) + "|" + hour
        if (!manual && prefs(app).getString("done_key", "") == key) return "이미 알렸어요"
        var result: String
        try {
            val items = loadTrack(app)
            val groups = LinkedHashMap<String, MutableList<Item>>()
            for (it in items) {
                val kind = if (manual) {
                    val d = parseDay(it.day)
                    if (d != null && today.timeInMillis - d.timeInMillis < 3L * 86400000L) "status" else null
                } else kindFor(it.day, today, hour)
                if (kind != null) groups.getOrPut(kind + "|" + it.day) { mutableListOf() }.add(it)
            }
            if (groups.isEmpty()) {
                result = if (manual) "최근 3일 안에 발송한 우체국 택배가 없어요" else "알릴 택배 없음"
            } else {
                val nos = groups.values.flatten().map { it.no }.distinct()
                val st = queryEpost(app, nos)
                // v1.8: 우체국 조회가 안 된 건(unknown)은 '도착 안 됨'으로 세지 않고 따로
                fun isUnknown(no: String): Boolean { val s = st[no]; return s == null || s.state == "error" || s.state == "unknown" }
                val unknownAll = nos.count { isUnknown(it) }
                val pendingAll = nos.count { (st[it]?.state ?: "") != "delivered" }
                val realLateAny = groups.any { (k, list) -> k.startsWith("late|") && list.any { !isUnknown(it.no) && st[it.no]?.state != "delivered" } }
                val p = prefs(app)
                val tries = if (p.getString("retry_key", "") == key) p.getInt("retry_n", 0) else 0
                if (!manual && unknownAll > 0 && tries < 3 && (!realLateAny || unknownAll * 2 >= pendingAll)) {
                    p.edit().putString("retry_key", key).putInt("retry_n", tries + 1).apply()
                    scheduleRetry(app, hour)
                    result = "우체국 조회가 안 된 ${unknownAll}건 → 5분 뒤 다시 확인 (${tries + 1}/3)"
                    val c0 = Calendar.getInstance()
                    p.edit().putString("last_result", "${c0.get(Calendar.MONTH) + 1}/${c0.get(Calendar.DAY_OF_MONTH)} ${c0.get(Calendar.HOUR_OF_DAY)}:${String.format("%02d", c0.get(Calendar.MINUTE))} — " + result).apply()
                    return result
                }
                val lines = mutableListOf<String>()
                var late = false
                var lateCount = 0
                var unknownCount = 0
                for ((k, list) in groups) {
                    val kind = k.substringBefore("|")
                    val day = k.substringAfter("|")
                    val md = day.substring(5).replace("-", "/").trimStart('0').replace("/0", "/")
                    val unknown = list.filter { isUnknown(it.no) }
                    val notYet = list.filter { !isUnknown(it.no) && st[it.no]?.state != "delivered" }
                    val done = list.size - notYet.size - unknown.size
                    unknownCount += unknown.size
                    if (kind == "late") {
                        if (notYet.isEmpty() && unknown.isEmpty()) continue
                        if (notYet.isNotEmpty()) {
                            late = true
                            lateCount += notYet.size
                            lines.add("🚨 $md 발송 · 아직 도착 안 됨 ${notYet.size}건")
                            for (x in notYet) lines.add(" · ${x.name} …${x.no.takeLast(4)} ${st[x.no]?.last ?: ""}".trimEnd())
                        }
                    } else {
                        // v2.0: 배송완료된 것은 알리지 않고, 아직 배송중인 것만
                        if (notYet.isEmpty() && unknown.isEmpty()) continue
                        if (notYet.isNotEmpty()) {
                            lines.add("🚚 $md 발송 · 아직 배송중 ${notYet.size}건" + (if (done > 0) " (배송완료 ${done}건 제외)" else ""))
                            for (x in notYet) lines.add(" · ${x.name} …${x.no.takeLast(4)} ${st[x.no]?.last ?: "배송중"}".trimEnd())
                        }
                    }
                    if (unknown.isNotEmpty()) {
                        lines.add("❔ $md 발송 · 우체국 조회가 안 된 ${unknown.size}건 (도착 여부 모름)")
                        lines.add(" · " + unknown.take(8).joinToString(", ") { "${it.name} …${it.no.takeLast(4)}" } + (if (unknown.size > 8) " 외 ${unknown.size - 8}건" else ""))
                    }
                }
                if (lines.isEmpty()) {
                    result = "모두 도착했어요 (알림 없음)"
                } else {
                    val title = if (late) "🚨 아직 도착 안 된 택배 ${lateCount}건" + (if (unknownCount > 0) " (조회 안 됨 ${unknownCount}건)" else "")
                        else if (groups.keys.any { it.startsWith("status|") } && lines.any { it.startsWith("🚚") }) "🚚 아직 배송중인 택배 (" + (if (manual) "지금" else "${hour}시") + ")"
                        else "❔ 우체국 배송 조회가 안 됐어요 (${unknownCount}건)"
                    notify(app, title, lines.joinToString("\n"), late)
                    result = title + "\n" + lines.joinToString("\n")
                }
            }
            if (!manual) prefs(app).edit().putString("done_key", key).putString("retry_key", "").apply()
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            if (msg.contains("PERMISSION_DENIED") || msg.contains("LOGIN_NEEDED")) {
                result = "🔒 플로팅 앱 로그인이 필요해요 (주문관리와 같은 아이디·비밀번호)"
                if (!manual && hour == 9) notify(app, "🔒 배송 알림: 로그인이 필요해요", "조아저씨쌈 앱을 열고 로그인해 주세요.", true)
            } else {
                result = "확인 실패: " + msg.take(120)
                if (!manual && hour == 9) notify(app, "⚠️ 배송 확인을 못 했어요", "인터넷 연결을 확인하고 주문관리 앱의 배송 현황을 열어 주세요.\n($result)", true)
            }
        }
        val c = Calendar.getInstance()
        prefs(app).edit().putString("last_result", "${c.get(Calendar.MONTH) + 1}/${c.get(Calendar.DAY_OF_MONTH)} ${c.get(Calendar.HOUR_OF_DAY)}:${String.format("%02d", c.get(Calendar.MINUTE))} — " + result).apply()
        return result
    }

    // ---------- v1.9 보안 로그인 (주문관리 앱과 같은 아이디·비밀번호) ----------
    fun secureEmailOf(id: String): String {
        val sb = StringBuilder("u")
        for (x in id.trim().toByteArray(Charsets.UTF_8)) sb.append(String.format("%02x", x.toInt() and 0xff))
        return sb.append("@joassam.app").toString()
    }
    fun securePwOf(id: String, pw: String): String = "jsm#" + pw.trim() + "#" + id.trim()

    /** 성공하면 "" , 실패하면 이유(한글) */
    fun login(ctx: Context, id: String, pw: String): String {
        val app = ctx.applicationContext
        if (id.trim().isEmpty()) return "아이디를 입력해 주세요"
        return try {
            fun q(x: String): String {
                val sb = StringBuilder("\"")
                for (ch in x) when {
                    ch == '"' -> sb.append("\\\"")
                    ch == '\\' -> sb.append("\\\\")
                    ch.code < 0x20 -> sb.append(String.format("\\u%04x", ch.code))
                    else -> sb.append(ch)
                }
                return sb.append("\"").toString()
            }
            val body = "{\"email\":" + q(secureEmailOf(id)) + ",\"password\":" + q(securePwOf(id, pw)) + ",\"returnSecureToken\":true}"
            val j = JSONObject(http("$authBase/v1/accounts:signInWithPassword?key=$API_KEY", "POST", body, "application/json", null, Common.baseUrl(app) + "/"))
            val rt = j.optString("refreshToken", "")
            if (rt.isEmpty()) return "로그인하지 못했어요"
            prefs(app).edit().putString("rt", rt).putString("rt_kind", "password").putString("sec_id", id.trim()).apply()
            ""
        } catch (e: Exception) {
            val m = e.message ?: ""
            when {
                m.contains("OPERATION_NOT_ALLOWED") -> "Firebase에서 이메일/비밀번호 로그인이 아직 꺼져 있어요"
                m.contains("INVALID") || m.contains("EMAIL_NOT_FOUND") || m.contains("PASSWORD") -> "아이디나 비밀번호가 맞지 않아요 (주문관리 앱에서 먼저 한 번 로그인해 주세요)"
                m.contains("TOO_MANY") -> "너무 많이 시도했어요. 잠시 후 다시 해 주세요"
                else -> "로그인 실패: " + m.take(80)
            }
        }
    }
    fun logout(ctx: Context) { prefs(ctx).edit().putString("rt", "").putString("rt_kind", "").putString("sec_id", "").apply() }
    /** v3.0: 주문관리 화면에서 로그인한 그 계정(갱신 토큰)을 그대로 씀 — 비밀번호는 앱에 저장하지 않음 */
    fun setToken(ctx: Context, refreshToken: String, id: String) {
        val rt = refreshToken.trim()
        if (rt.length < 20) return
        val p = prefs(ctx.applicationContext)
        if (p.getString("rt", "") == rt && p.getString("sec_id", "") == id.trim()) return
        p.edit().putString("rt", rt).putString("rt_kind", "password").putString("sec_id", id.trim()).apply()
    }
    fun loginText(ctx: Context): String {
        val p = prefs(ctx)
        return if (p.getString("rt_kind", "") == "password") "로그인: " + (p.getString("sec_id", "") ?: "") + " ✓"
        else "로그인 안 됨 — 보안 잠금을 켜면 로그인해야 배송 알림이 와요"
    }

    // ---------- 송장 목록 읽기 (로그인 토큰 → Firestore 문서) ----------
    private fun idToken(ctx: Context): String {
        val p = prefs(ctx)
        val rt = p.getString("rt", "") ?: ""
        val isPw = p.getString("rt_kind", "") == "password"
        if (rt.isNotEmpty()) {
            try {
                val body = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(rt, "UTF-8")
                val j = JSONObject(http("$tokenBase/v1/token?key=$API_KEY", "POST", body, "application/x-www-form-urlencoded", null, Common.baseUrl(ctx) + "/"))
                val tok = j.optString("id_token", "")
                if (tok.isNotEmpty()) {
                    p.edit().putString("rt", j.optString("refresh_token", rt)).apply()
                    return tok
                }
            } catch (e: Exception) {
                if (isPw) {
                    val m = e.message ?: ""
                    if (m.contains("HTTP 4")) { logout(ctx); throw Exception("LOGIN_NEEDED 로그인이 풀렸어요") }
                    throw e
                }
            }
        }
        if (isPw) throw Exception("LOGIN_NEEDED")
        val j = JSONObject(http("$authBase/v1/accounts:signUp?key=$API_KEY", "POST", "{\"returnSecureToken\":true}", "application/json", null, Common.baseUrl(ctx) + "/"))
        p.edit().putString("rt", j.optString("refreshToken", "")).apply()
        return j.getString("idToken")
    }

    fun loadTrack(ctx: Context): List<Item> {
        val tok = idToken(ctx)
        val url = "$firestoreBase/v1/projects/$PROJECT/databases/(default)/documents/stores/$TRACK_DOC"
        val doc = JSONObject(http(url, "GET", null, null, tok))
        val raw = doc.optJSONObject("fields")?.optJSONObject("json")?.optString("stringValue", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val no = o.optString("no", "")
            if (no.length >= 10) out.add(Item(no, o.optString("n", ""), o.optString("d", "")))
        }
        return out
    }

    // ---------- 우체국 조회 (주문관리 앱 서버 /api/epost) ----------
    fun queryEpost(ctx: Context, nos: List<String>): Map<String, Status> {
        // v1.8: 20개씩 보내면 서버가 시간 초과로 통째로 실패 → 4개씩 4개 동시에, 실패한 번호는 2개씩 한 번 더
        val out = java.util.concurrent.ConcurrentHashMap<String, Status>()
        val base = Common.baseUrl(ctx)
        fun pass(parts: List<List<String>>) {
            val ex = Executors.newFixedThreadPool(4)
            for (part in parts) {
                ex.execute {
                    try {
                        val j = JSONObject(http(base + "/api/epost?no=" + part.joinToString(","), "GET", null, null, null))
                        val res = j.optJSONArray("results")
                        if (res != null) {
                            for (i in 0 until res.length()) {
                                val r = res.getJSONObject(i)
                                val no = r.optString("no")
                                val stt = Status(r.optString("state", "unknown"), r.optString("last", ""))
                                val prev = out[no]
                                if (stt.state == "error" && prev != null && prev.state != "error") continue
                                out[no] = stt
                            }
                        }
                    } catch (e: Exception) {
                    }
                }
            }
            ex.shutdown()
            try { ex.awaitTermination(60, TimeUnit.SECONDS) } catch (e: Exception) {}
        }
        pass(nos.chunked(4))
        val miss = nos.filter { out[it] == null || out[it]?.state == "error" }
        if (miss.isNotEmpty()) {
            try { Thread.sleep(1500) } catch (e: Exception) {}
            pass(miss.chunked(2))
        }
        return HashMap(out)
    }

    private fun http(url: String, method: String, body: String?, type: String?, bearer: String?, referer: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 25000
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
        if (referer != null) c.setRequestProperty("Referer", referer)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", type ?: "application/json")
            val w = OutputStreamWriter(c.outputStream, "UTF-8")
            w.write(body)
            w.flush()
            w.close()
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) throw RuntimeException("HTTP $code " + text.take(80))
        return text
    }

    // ---------- 알림 ----------
    private fun notify(ctx: Context, title: String, text: String, urgent: Boolean) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "배송 알림", NotificationManager.IMPORTANCE_HIGH))
        }
        // v3.0: 알림을 누르면 이 앱의 '배송' 탭으로
        val open = Intent(ctx, MainActivity::class.java)
        open.putExtra(MainActivity.EXTRA_TAB, "ship")
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(ctx, 2602, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = Notification.Builder(ctx, CHANNEL)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull() ?: text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(if (urgent) 2604 else 2603, n)
    }
}
