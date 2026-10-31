package com.joassam.floating

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * v3.0 통합 앱: 주문관리 화면(웹)을 앱 안에서 그대로 보여 주고,
 * 플로팅 버튼·배송 알림·음성 인식·엑셀 저장을 같은 앱에서 처리
 */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_TEXT = "joassam_text"
        const val EXTRA_SENDER = "joassam_sender"
        const val EXTRA_PHONE = "joassam_phone"
        const val EXTRA_OCR = "joassam_ocr"
        const val EXTRA_TAB = "joassam_tab"
        private const val REQ_FILE = 9101
        private const val REQ_MIC = 9102
        private const val REQ_STORAGE = 9103
        private const val REQ_MIC_WEB = 9104
    }

    private class Share(val text: String, val sender: String, val phone: String, val ocr: Boolean)
    private class PendingSave(val name: String, val mime: String, val bytes: ByteArray)

    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())
    private var bridgeJs: String = ""
    private var webReady = false
    private var pendingShare: Share? = null
    private var pendingTab: String? = null
    private var pendingFloat = false
    private var pendingSave: PendingSave? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null
    private var webPermission: PermissionRequest? = null
    private var lastBack = 0L
    private val shareFallback = Runnable { fallbackShare() }

    // 음성 인식
    private var recognizer: SpeechRecognizer? = null
    private var speechId = 0
    private var speechCont = false
    private var speechInterim = false
    private var speechMax = 1
    private var speechStopping = false
    private var speechStarted = false
    private var speechLang = "ko-KR"
    private var pendingSpeech: IntArray? = null

    // ------------------------------------------------------------------ 시작

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { DeliveryCheck.schedule(this) } catch (e: Exception) {}
        if (!Updater.triedRecently(this) && Updater.installIfReady(this)) {
            Toast.makeText(this, "새 버전으로 업데이트 중이에요", Toast.LENGTH_LONG).show()
        } else {
            Updater.checkAndDownload(this, false)
        }
        bridgeJs = try { assets.open("bridge.js").bufferedReader(Charsets.UTF_8).use { it.readText() } } catch (e: Exception) { "" }

        web = WebView(this)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        @Suppress("DEPRECATION")
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(false)
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.userAgentString = s.userAgentString + " JoassamApp/3"
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Bridge(), "JoassamApp")
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        setContentView(web)

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        if (web.url == null) web.loadUrl(Common.baseUrl(this) + "/")
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try { web.saveState(outState) } catch (e: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        if (pendingFloat && Settings.canDrawOverlays(this)) {
            pendingFloat = false
            startFloating()
        }
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        try { recognizer?.destroy() } catch (e: Exception) {}
        recognizer = null
        ui.removeCallbacks(shareFallback)
        try { web.destroy() } catch (e: Exception) {}
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) { web.goBack(); return }
        val now = System.currentTimeMillis()
        if (now - lastBack < 2000) { moveTaskToBack(true); return }
        lastBack = now
        Toast.makeText(this, "한 번 더 누르면 닫혀요", Toast.LENGTH_SHORT).show()
    }

    /** 플로팅 [등록]에서 온 주문 문자, 알림에서 온 탭, 예전 주소(joassamfloat://) 처리 */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.data?.scheme == "joassamfloat") {
            i.data = null
            startFloating()
            return
        }
        val text = i.getStringExtra(EXTRA_TEXT)
        if (!text.isNullOrBlank()) {
            pendingShare = Share(text, i.getStringExtra(EXTRA_SENDER) ?: "", i.getStringExtra(EXTRA_PHONE) ?: "", i.getBooleanExtra(EXTRA_OCR, false))
            i.removeExtra(EXTRA_TEXT)
            deliverPending()
        }
        val tab = i.getStringExtra(EXTRA_TAB)
        if (!tab.isNullOrBlank()) {
            pendingTab = tab
            i.removeExtra(EXTRA_TAB)
            deliverPending()
        }
    }

    /** 화면이 준비됐으면(로그인·자료 불러오기 끝) 바로 넘기고, 아니면 기다렸다가 넘김 */
    private fun deliverPending() {
        val sh = pendingShare
        if (sh != null) {
            if (webReady) {
                pendingShare = null
                ui.removeCallbacks(shareFallback)
                web.evaluateJavascript(AppLogic.sharedJs(sh.text, sh.sender, sh.phone, sh.ocr)) { r ->
                    if (r != "true") web.loadUrl(AppLogic.floatUrl(Common.baseUrl(this), sh.text, sh.sender, sh.ocr, sh.phone))
                }
            } else {
                ui.removeCallbacks(shareFallback)
                ui.postDelayed(shareFallback, 10000)
            }
        }
        val tab = pendingTab
        if (tab != null && webReady) {
            pendingTab = null
            AppLogic.tabJs(tab)?.let { web.evaluateJavascript(it, null) }
        }
    }

    /** 10초가 지나도 화면이 준비 안 되면(예전 버전 화면 등) 예전 방식 주소로 열기 — 주소의 문자는 화면이 준비되면 자동으로 처리됨 */
    private fun fallbackShare() {
        val sh = pendingShare ?: return
        if (webReady) { deliverPending(); return }
        pendingShare = null
        web.loadUrl(AppLogic.floatUrl(Common.baseUrl(this), sh.text, sh.sender, sh.ocr, sh.phone))
    }

    // ------------------------------------------------------------------ 플로팅

    private fun startFloating() {
        if (!Settings.canDrawOverlays(this)) {
            pendingFloat = true
            Toast.makeText(this, "'다른 앱 위에 표시'를 허용해 주세요. 허용하고 돌아오면 플로팅 버튼이 떠요", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            return
        }
        val s = Intent(this, BubbleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(s) else startService(s)
        // 예전처럼: 플로팅을 켜면 홈 화면으로 → 카톡·문자에서 [등록]
        moveTaskToBack(true)
    }

    // ------------------------------------------------------------------ 파일 저장 (엑셀 등)

    private fun hasStorage(): Boolean =
        Build.VERSION.SDK_INT >= 29 ||
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun saveBytes(name: String, mime: String, bytes: ByteArray) {
        if (!hasStorage()) {
            pendingSave = PendingSave(name, mime, bytes)
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        Thread {
            var uri: Uri? = null
            var err = ""
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues()
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                        ?: throw Exception("저장할 곳을 만들지 못했어요")
                    val os = contentResolver.openOutputStream(u) ?: throw Exception("파일을 열지 못했어요")
                    os.use { it.write(bytes) }
                    uri = u
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    var f = File(dir, name)
                    var n = 1
                    val dot = name.lastIndexOf('.')
                    while (f.exists() && n < 100) {
                        f = File(dir, if (dot > 0) name.substring(0, dot) + "($n)" + name.substring(dot) else "$name($n)")
                        n++
                    }
                    FileOutputStream(f).use { it.write(bytes) }
                    uri = FileProvider.getUriForFile(this, "$packageName.files", f)
                }
            } catch (e: Exception) {
                err = e.message ?: "알 수 없는 오류"
            }
            runOnUiThread {
                val u = uri
                if (u == null) {
                    Toast.makeText(this, "파일을 저장하지 못했어요: $err", Toast.LENGTH_LONG).show()
                } else {
                    showSaved(name, mime, u)
                }
            }
        }.start()
    }

    private fun showSaved(name: String, mime: String, uri: Uri) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("저장했어요")
            .setMessage("휴대폰 '다운로드' 폴더에 저장했어요.\n\n$name")
            .setPositiveButton("열기") { _, _ -> openUri(uri, mime, false) }
            .setNeutralButton("보내기") { _, _ -> openUri(uri, mime, true) }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun openUri(uri: Uri, mime: String, send: Boolean) {
        try {
            val i = if (send) {
                Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            } else {
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, if (send) "보내기" else "열기"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "이 파일을 열 앱이 없어요 (엑셀 앱을 설치해 주세요)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "열지 못했어요: " + (e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ 권한 결과

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_STORAGE -> {
                val p = pendingSave
                pendingSave = null
                if (p != null) {
                    if (ok) saveBytes(p.name, p.mime, p.bytes)
                    else Toast.makeText(this, "저장 권한이 없어서 파일을 저장하지 못했어요", Toast.LENGTH_LONG).show()
                }
            }
            REQ_MIC -> {
                val p = pendingSpeech
                pendingSpeech = null
                if (p != null) {
                    if (ok) listen() else { emitSpeech(p[0], "error", "{\"error\":\"not-allowed\"}"); emitSpeech(p[0], "end", null) }
                }
            }
            REQ_MIC_WEB -> {
                val r = webPermission
                webPermission = null
                if (r != null) {
                    if (ok) r.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else r.deny()
                }
            }
        }
    }

    // ------------------------------------------------------------------ 파일 고르기 (엑셀 올리기·사진)

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE) return
        val cb = fileCb
        fileCb = null
        if (cb == null) return
        var result: Array<Uri>? = null
        if (resultCode == RESULT_OK) {
            val clip = data?.clipData
            if (clip != null && clip.itemCount > 0) {
                result = Array(clip.itemCount) { k -> clip.getItemAt(k).uri }
            } else if (data?.data != null) {
                result = arrayOf(data.data!!)
            } else if (cameraUri != null) {
                result = arrayOf(cameraUri!!)
            }
        }
        cameraUri = null
        cb.onReceiveValue(result)
    }

    // ------------------------------------------------------------------ 음성 인식 (웹뷰에 없는 기능을 안드로이드로)

    private fun hasMic(): Boolean = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun emitSpeech(id: Int, kind: String, json: String?) {
        web.evaluateJavascript(AppLogic.speechJs(id, kind, json), null)
    }

    private fun startSpeech(id: Int, lang: String, cont: Boolean, interim: Boolean, max: Int) {
        val prev = speechId
        if (prev != 0 && prev != id && speechStarted) {
            speechStopping = true
            try { recognizer?.cancel() } catch (e: Exception) {}
            emitSpeech(prev, "end", null)
        }
        speechId = id
        speechLang = lang
        speechCont = cont
        speechInterim = interim
        speechMax = if (max < 1) 1 else if (max > 10) 10 else max
        speechStopping = false
        speechStarted = false
        if (!hasMic()) {
            pendingSpeech = intArrayOf(id)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        listen()
    }

    private fun listen() {
        val id = speechId
        try {
            val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
            r.setRecognitionListener(Listener(id))
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLang)
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, speechInterim)
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, speechMax)
            r.startListening(i)
        } catch (e: Exception) {
            emitSpeech(id, "error", "{\"error\":\"audio-capture\"}")
            emitSpeech(id, "end", null)
            speechId = 0
        }
    }

    private fun stopSpeech(id: Int, abort: Boolean) {
        if (id != speechId) return
        speechStopping = true
        try {
            if (abort) {
                recognizer?.cancel()
                emitSpeech(id, "end", null)
                speechId = 0
            } else {
                recognizer?.stopListening()
            }
        } catch (e: Exception) {
            emitSpeech(id, "end", null)
            speechId = 0
        }
    }

    private inner class Listener(private val id: Int) : RecognitionListener {
        private fun live(): Boolean = id == speechId
        override fun onReadyForSpeech(params: Bundle?) {
            if (!live()) return
            if (!speechStarted) {
                speechStarted = true
                emitSpeech(id, "start", null)
                emitSpeech(id, "audiostart", null)
            }
        }
        override fun onBeginningOfSpeech() { if (live()) emitSpeech(id, "speechstart", null) }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { if (live()) emitSpeech(id, "speechend", null) }
        override fun onError(error: Int) {
            if (!live()) return
            if (speechCont && !speechStopping && AppLogic.speechRetryable(error)) { listen(); return }
            if (!(speechStopping && (error == 5 || error == 7))) emitSpeech(id, "error", "{\"error\":" + AppLogic.js(AppLogic.speechError(error)) + "}")
            emitSpeech(id, "end", null)
            speechId = 0
        }
        override fun onResults(results: Bundle?) {
            if (!live()) return
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            val conf = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            if (list.isNotEmpty()) emitSpeech(id, "final", AppLogic.altsJson(list, conf, speechMax))
            if (speechCont && !speechStopping) { listen(); return }
            emitSpeech(id, "end", null)
            speechId = 0
        }
        override fun onPartialResults(partialResults: Bundle?) {
            if (!live() || !speechInterim) return
            val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
            if (list.isEmpty() || list[0].isBlank()) return
            emitSpeech(id, "partial", AppLogic.altsJson(list, null, 1))
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ------------------------------------------------------------------ 화면(웹)과 이어 주는 창구

    private inner class Bridge {
        @JavascriptInterface fun isApp(): Boolean = true
        @JavascriptInterface fun appVersion(): String = "3.0 (" + Updater.currentCode(this@MainActivity) + ")"
        @JavascriptInterface fun startFloating() { runOnUiThread { this@MainActivity.startFloating() } }
        @JavascriptInterface fun stopFloating() { runOnUiThread { stopService(Intent(this@MainActivity, BubbleService::class.java)) } }
        @JavascriptInterface fun openSettings() { runOnUiThread { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) } }
        @JavascriptInterface fun webReady() {
            runOnUiThread { webReady = true; deliverPending() }
        }
        @JavascriptInterface fun setAuth(refreshToken: String?, id: String?) {
            val rt = refreshToken ?: return
            val ctx = applicationContext
            Thread { try { DeliveryCheck.setToken(ctx, rt, id ?: ""); DeliveryCheck.schedule(ctx) } catch (e: Exception) {} }.start()
        }
        @JavascriptInterface fun clearAuth() {
            val ctx = applicationContext
            Thread { try { DeliveryCheck.logout(ctx) } catch (e: Exception) {} }.start()
        }
        @JavascriptInterface fun saveFile(name: String?, mime: String?, base64: String?) {
            val nm = AppLogic.safeFileName(name)
            val mt = AppLogic.mimeFor(nm, mime)
            val bytes = try { Base64.decode(base64 ?: "", Base64.DEFAULT) } catch (e: Exception) { null }
            runOnUiThread {
                if (bytes == null || bytes.isEmpty()) Toast.makeText(this@MainActivity, "파일 내용이 비어 있어서 저장하지 못했어요", Toast.LENGTH_LONG).show()
                else saveBytes(nm, mt, bytes)
            }
        }
        @JavascriptInterface fun saveFailed(name: String?) {
            runOnUiThread { Toast.makeText(this@MainActivity, "파일을 만들지 못했어요: " + (name ?: ""), Toast.LENGTH_LONG).show() }
        }
        @JavascriptInterface fun hasSpeech(): Boolean = try { SpeechRecognizer.isRecognitionAvailable(this@MainActivity) } catch (e: Exception) { false }
        @JavascriptInterface fun speechStart(id: Int, lang: String?, continuous: Boolean, interim: Boolean, max: Int): Boolean {
            runOnUiThread { startSpeech(id, lang ?: "ko-KR", continuous, interim, max) }
            return true
        }
        @JavascriptInterface fun speechStop(id: Int) { runOnUiThread { stopSpeech(id, false) } }
        @JavascriptInterface fun speechAbort(id: Int) { runOnUiThread { stopSpeech(id, true) } }
    }

    // ------------------------------------------------------------------ 웹 화면 처리

    private fun openOutside(uri: Uri): Boolean {
        return try {
            val i = Intent(Intent.ACTION_VIEW, uri)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 앱 안에서 열지(false), 앱이 따로 처리할지(true) */
    private fun route(url: String): Boolean {
        val uri = Uri.parse(url)
        val scheme = (uri.scheme ?: "").lowercase()
        if (scheme == "joassamfloat") { startFloating(); return true }
        if (scheme == "intent") {
            return try {
                val it = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                if (it.data?.scheme == "joassamfloat" || it.scheme == "joassamfloat") {
                    startFloating()
                } else {
                    it.addCategory(Intent.CATEGORY_BROWSABLE)
                    it.component = null
                    it.selector = null
                    try {
                        startActivity(it)
                    } catch (e: ActivityNotFoundException) {
                        val fb = it.getStringExtra("browser_fallback_url")
                        if (!fb.isNullOrBlank()) web.loadUrl(fb)
                    }
                }
                true
            } catch (e: Exception) {
                true
            }
        }
        if (scheme == "http" || scheme == "https") {
            if (AppLogic.isInternal(url, Common.baseUrl(this))) return false
            openOutside(uri)
            return true
        }
        if (scheme == "about" || scheme == "blob" || scheme == "data" || scheme == "javascript") return false
        openOutside(uri) // tel:, sms:, mailto:, market: 등
        return true
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            return route(request.url.toString())
        }

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = route(url)

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            webReady = false
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            if (bridgeJs.isNotEmpty()) view.evaluateJavascript(bridgeJs, null)
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            super.onReceivedError(view, request, error)
            if (!request.isForMainFrame) return
            val base = Common.baseUrl(this@MainActivity) + "/"
            val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
                "<body style='font-family:sans-serif;padding:32px;text-align:center;color:#1B2430'>" +
                "<div style='font-size:42px'>📶</div><h3>인터넷에 연결되지 않았어요</h3>" +
                "<p style='color:#5F5E5A'>와이파이나 데이터를 확인한 뒤 다시 눌러 주세요.</p>" +
                "<button style='font-size:17px;padding:12px 28px;border:none;border-radius:10px;background:#2F6F4E;color:#fff' " +
                "onclick=\"location.href='" + base + "'\">다시 시도</button></body></html>"
            view.loadDataWithBaseURL(base, html, "text/html", "UTF-8", null)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // 화면 엔진이 멈추면 앱이 꺼지지 않게 새로 띄움
            try { (view.parent as? android.view.ViewGroup)?.removeView(view); view.destroy() } catch (e: Exception) {}
            recreate()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
            fileCb?.onReceiveValue(null)
            fileCb = callback
            cameraUri = null
            return try {
                val pick = params.createIntent()
                pick.addCategory(Intent.CATEGORY_OPENABLE)
                if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                val accept = params.acceptTypes.joinToString(",").lowercase()
                val wantsImage = accept.contains("image")
                val chooser: Intent
                if (wantsImage) {
                    val dir = File(cacheDir, "camera")
                    dir.mkdirs()
                    val f = File(dir, "photo_" + System.currentTimeMillis() + ".jpg")
                    val u = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
                    cameraUri = u
                    val cam = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    cam.putExtra(MediaStore.EXTRA_OUTPUT, u)
                    cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (params.isCaptureEnabled) {
                        chooser = cam
                    } else {
                        chooser = Intent.createChooser(pick, "사진 고르기")
                        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cam))
                    }
                } else {
                    chooser = Intent.createChooser(pick, "파일 고르기")
                }
                @Suppress("DEPRECATION")
                startActivityForResult(chooser, REQ_FILE)
                true
            } catch (e: Exception) {
                fileCb = null
                cameraUri = null
                Toast.makeText(this@MainActivity, "파일을 고를 수 없어요: " + (e.message ?: ""), Toast.LENGTH_LONG).show()
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                val wantsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!wantsMic || !AppLogic.isInternal(request.origin.toString(), Common.baseUrl(this@MainActivity))) {
                    request.deny()
                } else if (hasMic()) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    webPermission?.deny()
                    webPermission = request
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC_WEB)
                }
            }
        }
    }
}
