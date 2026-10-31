package com.joassam.floating

/** 화면(안드로이드)과 상관없는 순수 계산 — 컴퓨터에서 바로 시험할 수 있게 따로 둠 */
object AppLogic {

    /** 주문관리 화면 안에서 열 주소인지 (같은 사이트면 앱 안, 다른 사이트는 휴대폰 브라우저) */
    fun isInternal(url: String, base: String): Boolean {
        val h = hostOf(url) ?: return false
        val b = hostOf(base) ?: return false
        return h.equals(b, ignoreCase = true)
    }

    fun hostOf(url: String): String? {
        val m = Regex("^https?://([^/?#:]+)", RegexOption.IGNORE_CASE).find(url.trim()) ?: return null
        return m.groupValues[1]
    }

    /** 파일 이름에서 저장할 수 없는 글자 빼기 (한글·공백·괄호는 그대로) */
    fun safeFileName(name: String?): String {
        var s = (name ?: "").replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.')
        if (s.isEmpty()) s = "download"
        if (s.length > 120) {
            val dot = s.lastIndexOf('.')
            val ext = if (dot > 0 && s.length - dot <= 8) s.substring(dot) else ""
            s = s.substring(0, 120 - ext.length) + ext
        }
        return s
    }

    fun mimeFor(name: String, given: String?): String {
        val n = name.lowercase()
        return when {
            n.endsWith(".xls") -> "application/vnd.ms-excel"
            n.endsWith(".xlsx") -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            n.endsWith(".csv") -> "text/csv"
            n.endsWith(".json") -> "application/json"
            n.endsWith(".pdf") -> "application/pdf"
            n.endsWith(".png") -> "image/png"
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
            n.endsWith(".txt") -> "text/plain"
            !given.isNullOrBlank() -> given
            else -> "application/octet-stream"
        }
    }

    /** 자바스크립트 문자열로 안전하게 감싸기 */
    fun js(s: String?): String {
        val x = s ?: ""
        val sb = StringBuilder("\"")
        for (ch in x) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch == ' ' -> sb.append("\\u2028")
                ch == ' ' -> sb.append("\\u2029")
                ch == '<' -> sb.append("\\u003c")
                ch.code < 0x20 -> sb.append(String.format("\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append("\"").toString()
    }

    /** 플로팅에서 읽은 주문 문자를 화면에 넘기는 코드 (화면이 받으면 true) */
    fun sharedJs(text: String, sender: String, phone: String, ocr: Boolean): String =
        "(function(){try{return !!(window.__joassamShared&&window.__joassamShared({text:" + js(text) +
            ",sender:" + js(sender) + ",senderPhone:" + js(phone) + ",fromOcr:" + (if (ocr) "true" else "false") +
            "}));}catch(e){return false;}})()"

    /** 화면이 아직 받을 준비가 안 됐을 때 쓰는 예전 방식 주소 (?from=float&text=…) */
    fun floatUrl(base: String, text: String, sender: String, ocr: Boolean, phone: String): String =
        base.trimEnd('/') + "/?from=float" +
            (if (phone.isNotBlank()) "&senderPhone=" + enc(phone) else "") +
            (if (ocr) "&src=ocr" else "") +
            "&text=" + enc(text) +
            (if (sender.isNotBlank()) "&sender=" + enc(sender) else "")

    fun enc(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if ((ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '-' || ch == '_' || ch == '.' || ch == '~') sb.append(ch)
            else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
        return sb.toString()
    }

    /** 안드로이드 음성 인식 오류 번호 → 크롬과 같은 이름 (화면 코드가 그대로 알아듣게) */
    fun speechError(code: Int): String = when (code) {
        1, 2, 4, 11 -> "network"           // 네트워크 시간초과·네트워크·서버·서버 끊김
        3 -> "audio-capture"                // 마이크
        5, 8 -> "aborted"                   // 클라이언트·인식기 사용 중
        6, 7 -> "no-speech"                 // 말소리 없음·못 알아들음
        9 -> "not-allowed"                  // 마이크 권한 없음
        10 -> "network"                     // 요청 너무 많음
        12, 13 -> "language-not-supported"
        else -> "aborted"
    }

    /** 이어 듣기(continuous)일 때 다시 들어도 되는 오류인지 */
    fun speechRetryable(code: Int): Boolean = code == 6 || code == 7

    /** 음성 인식 결과 → 화면에 넘길 JSON {alts:[{t,c}]} */
    fun altsJson(texts: List<String>, conf: FloatArray?, max: Int): String {
        val sb = StringBuilder("{\"alts\":[")
        val n = minOf(texts.size, maxOf(1, max))
        for (i in 0 until n) {
            if (i > 0) sb.append(',')
            val c = if (conf != null && i < conf.size && conf[i] >= 0f) conf[i] else if (i == 0) 0.9f else 0.5f
            sb.append("{\"t\":").append(js(texts[i])).append(",\"c\":").append(String.format(java.util.Locale.US, "%.3f", c)).append('}')
        }
        return sb.append("]}").toString()
    }

    fun speechJs(id: Int, kind: String, json: String?): String =
        "window.__joassamSpeech&&window.__joassamSpeech(" + id + "," + js(kind) + "," + (json ?: "null") + ")"

    /** 앱에서 열 탭 이름 (영문 소문자만 허용) */
    fun tabJs(tab: String): String? {
        if (!Regex("^[a-z]{2,20}$").matches(tab)) return null
        return "window.__joassamOpenTab&&window.__joassamOpenTab(" + js(tab) + ")"
    }
}
