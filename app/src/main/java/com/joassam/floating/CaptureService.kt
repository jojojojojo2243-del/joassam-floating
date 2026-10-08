package com.joassam.floating

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

/** 화면을 한 번 찍어서 채팅방 제목(= 주문자)을 글자 인식 → 주문관리 앱 열기 */
class CaptureService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var finished = false
    private var clip = ""
    private var ocrBody = false
    private var senderPhone = ""

    /** 제목 줄에서 앞쪽 한글 이름만 남기기: "조숙희고객님 v2그라오행:" → "조숙희고객님" */
    private fun cleanTitle(raw: String): String {
        val s = raw.trim().replace(Regex("^[<‹〈←\\s]+"), "")
        val m = Regex("[가-힣]{2,}(?:\\s?[가-힣]{1,6}){0,2}").find(s) ?: return s
        return m.value.trim()
    }

    /** 휴대폰 연락처에서 대화방 이름과 같은 사람 찾기 → (연락처 이름, 전화번호) */
    private fun lookupContact(title: String): Pair<String, String>? {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val t = title.replace(Regex("\\s+"), "")
        var best: Pair<String, String>? = null
        var bestLen = 0
        try {
            val cr = contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            ) ?: return null
            cr.use { c ->
                while (c.moveToNext()) {
                    val name = (c.getString(0) ?: "").replace(Regex("\\s+"), "")
                    val num = (c.getString(1) ?: "").replace(Regex("[^0-9]"), "")
                    if (name.length < 2 || num.length < 9) continue
                    // 정확히 같거나, 화면 글자 인식으로 앞에 1~2글자가 더 붙은 경우
                    val rest = if (t.startsWith(name)) t.substring(name.length) else null
                    val ok = name == t || (t.endsWith(name) && t.length - name.length <= 2) ||
                        (rest != null && Regex("^(고객님|고객|님)?$").matches(rest))
                    if (ok && name.length > bestLen) { bestLen = name.length; best = Pair(c.getString(0) ?: name, num) }
                }
            }
        } catch (_: Exception) {}
        return best
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Common.notification(this, "주문 문자 읽는 중…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(2, n)
        }
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        clip = intent.getStringExtra("clip") ?: ""
        val code = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("data", Intent::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra("data")
        if (data == null) { finish(""); return START_NOT_STICKY }

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(code, data)
            projection = mp
            // 안드로이드 14부터 캡처 전에 콜백 등록이 필수
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { cleanup() }
            }, handler)
            // 투명 화면이 닫히고 채팅 화면이 다시 보일 때까지 잠깐 기다림
            handler.postDelayed({ capture(mp) }, 700)
            // 8초 안에 못 끝내면 주문자 없이 진행
            handler.postDelayed({ finish("") }, 8000)
        } catch (e: Exception) {
            finish("")
        }
        return START_NOT_STICKY
    }

    private fun screenSize(): Triple<Int, Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            Triple(m.widthPixels, m.heightPixels, dpi)
        }
    }

    private fun capture(mp: MediaProjection) {
        if (finished) return
        try {
            val (w, h, dpi) = screenSize()
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader = r
            var got = false
            r.setOnImageAvailableListener({ ir ->
                val img = try { ir.acquireLatestImage() } catch (e: Exception) { null }
                if (img == null) return@setOnImageAvailableListener
                if (got || finished) { img.close(); return@setOnImageAvailableListener }
                got = true
                var bmp: Bitmap? = null
                try {
                    val plane = img.planes[0]
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * w
                    val full = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                    full.copyPixelsFromBuffer(plane.buffer)
                    bmp = Bitmap.createBitmap(full, 0, 0, w, h)
                } catch (e: Exception) {
                } finally {
                    img.close()
                }
                cleanup()
                if (bmp == null) finish("") else recognize(bmp, w, h)
            }, handler)
            display = mp.createVirtualDisplay(
                "joassam-capture", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
            )
        } catch (e: Exception) {
            finish("")
        }
    }

    /** 화면 전체를 한글 인식: 위쪽에서 채팅방 제목(주문자), 가운데에서 문자 내용 */
    private fun recognize(bmp: Bitmap, w: Int, h: Int) {
        try {
            val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { t -> finishWith(t, h) }
                .addOnFailureListener { finish("") }
        } catch (e: Exception) {
            finish("")
        }
    }

    private val phoneRe = Regex("01[016789][-\\s.]?\\d{3,4}[-\\s.]?\\d{4}")

    private fun finishWith(t: Text, h: Int) {
        val top = (h * 0.025).toInt()
        val titleBottom = (h * 0.14).toInt()
        val bodyBottom = (h * 0.88).toInt()
        val titleLines = ArrayList<Text.Line>()
        val bodyLines = ArrayList<Pair<Int, String>>()
        for (block in t.textBlocks) for (line in block.lines) {
            val box = line.boundingBox ?: continue
            val cy = box.centerY()
            if (cy in top until titleBottom) titleLines.add(line)
            else if (cy in titleBottom until bodyBottom) bodyLines.add(Pair(box.top, line.text.trim()))
        }
        var sender = pickTitle(titleLines)
        // 대화방 제목이 전화번호인 경우(저장 안 된 번호)
        if (sender.isBlank()) {
            for (l in titleLines) { val m = phoneRe.find(l.text); if (m != null) { senderPhone = m.value.replace(Regex("[^0-9]"), ""); break } }
        } else {
            val hit = lookupContact(sender)
            if (hit != null) { sender = hit.first; senderPhone = hit.second }
        }
        // 복사한 내용에 전화번호가 있으면 그대로, 없으면 화면에서 읽은 글자 사용
        if (phoneRe.containsMatchIn(clip) || bodyLines.isEmpty()) {
            finish(sender)
        } else {
            bodyLines.sortBy { it.first }
            val body = bodyLines.map { it.second }
                .filter { it.isNotBlank() && !Regex("^(오전|오후)\\s*\\d{1,2}:\\d{2}$").matches(it) }
                .joinToString("\n")
            if (phoneRe.containsMatchIn(body)) { clip = body; ocrBody = true }
            finish(sender)
        }
    }

    /** 인식된 줄 중에서 '채팅방 제목'으로 보이는 줄 고르기: 한글이 있고, 시간·배터리 같은 게 아니고, 글씨가 가장 큰 줄 */
    private fun pickTitle(lines: List<Text.Line>): String {
        var best = ""
        var bestH = 0
        for (line in lines) {
            // 제목 앞의 동그란 프로필 글자(예: "김")는 빼기
            val els = line.elements
            var s = if (els.size >= 2 && els[0].text.trim().length == 1) els.drop(1).joinToString(" ") { it.text } else line.text
            s = cleanTitle(s)
            s = s.replace(Regex("^[<‹〈←\\s]+"), "").replace(Regex("[∨˅⌄vV>›〉\\s]+$"), "").trim()
            if (s.length < 2 || s.length > 20) continue
            if (!Regex("[가-힣]").containsMatchIn(s)) continue
            if (Regex("^\\d{1,2}:\\d{2}").containsMatchIn(s)) continue
            if (Regex("(오전|오후)\\s*\\d").containsMatchIn(s)) continue
            if (Regex("^(검색|메시지|채팅|대화|전화|통화|입력|보내기)$").matches(s)) continue
            val hgt = line.boundingBox?.height() ?: 0
            if (hgt > bestH) { bestH = hgt; best = s }
        }
        return best
    }

    private fun cleanup() {
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }

    private fun finish(sender: String) {
        if (finished) return
        finished = true
        cleanup()
        if (clip.isBlank()) {
            BubbleService.instance?.setVisible(true)
            android.widget.Toast.makeText(this, "주문 문자를 읽지 못했어요. 문자를 길게 눌러 '복사'한 뒤 다시 눌러주세요", android.widget.Toast.LENGTH_LONG).show()
        } else {
            try { Common.openOrderApp(this, clip, sender, ocrBody, senderPhone) } catch (_: Exception) {}
            // 등록을 넘겼으면 플로팅 버튼은 닫기 (다음 주문 때 주문관리 앱의 + 로 다시 켜짐)
            BubbleService.instance?.stopSelf()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
