package com.joassam.floating

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 배송 알림(9·12·16시) 알람 예약 — 앱을 열 때마다 다시 맞춰 둠
        try { DeliveryCheck.schedule(this) } catch (e: Exception) {}
        // 주문관리 앱의 + 버튼에서 왔고 권한이 있으면: 플로팅 켜고 바로 홈 화면으로
        if (handleDeepLink(intent)) return
        // 앱을 직접 열었을 때: 받아 둔 새 버전이 있으면 바로 설치, 없으면 조용히 확인
        if (!Updater.triedRecently(this) && Updater.installIfReady(this)) {
            Toast.makeText(this, "새 버전으로 업데이트 중이에요", Toast.LENGTH_LONG).show()
        } else {
            Updater.checkAndDownload(this, false)
        }
        buildUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (::status.isInitialized) refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshStatus()
    }

    private fun handleDeepLink(i: Intent?): Boolean {
        if (i?.data?.scheme != "joassamfloat") return false
        if (!Settings.canDrawOverlays(this)) return false
        // 새 버전을 이미 받아 두었으면 먼저 설치 (설치되면 앱이 새로 시작되니 + 를 한 번 더 눌러 주세요)
        if (!Updater.triedRecently(this) && Updater.installIfReady(this)) {
            Toast.makeText(this, "새 버전으로 업데이트 중이에요. 잠시 후 주문관리 앱에서 + 를 다시 눌러 주세요", Toast.LENGTH_LONG).show()
            finish()
            return true
        }
        Updater.checkAndDownload(this, false)
        startBubble()
        val home = Intent(Intent.ACTION_MAIN)
        home.addCategory(Intent.CATEGORY_HOME)
        home.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(home)
        finish()
        return true
    }

    private fun startBubble() {
        val s = Intent(this, BubbleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(s) else startService(s)
    }

    private fun btn(text: String, color: Int, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 16f
        b.setTextColor(Color.WHITE)
        b.setBackgroundColor(color)
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = 24
        b.layoutParams = lp
        return b
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 96, 48, 48)

        val title = TextView(this)
        title.text = "조아저씨쌈 플로팅"
        title.textSize = 24f
        title.setTextColor(Color.parseColor("#1B2430"))
        root.addView(title)

        val desc = TextView(this)
        desc.text = "사용 방법\n" +
            "1. 아래 '다른 앱 위에 표시 허용'을 켜주세요 (처음 한 번)\n" +
            "2. 주문관리 앱에서 + 버튼을 누르면 플로팅 버튼이 떠요\n" +
            "3. 문자나 카톡에서 주문 문자를 길게 눌러 '복사'\n" +
            "4. 플로팅 [등록] 버튼 → 화면 공유에서 그 채팅 앱 선택\n" +
            "5. 주문자(대화방 이름·연락처 번호)와 문자가 주문관리 앱으로 자동 입력돼요"
        desc.textSize = 14f
        desc.setTextColor(Color.parseColor("#5F5E5A"))
        desc.setPadding(0, 24, 0, 8)
        root.addView(desc)

        status = TextView(this)
        status.textSize = 14f
        status.setPadding(0, 16, 0, 0)
        root.addView(status)

        root.addView(btn("1. 다른 앱 위에 표시 허용", Color.parseColor("#854F0B")) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(btn("2. 알림 허용 (플로팅 유지에 필요)", Color.parseColor("#185FA5")) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
            } else {
                Toast.makeText(this, "이 휴대폰은 따로 허용할 필요가 없어요", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(btn("3. 연락처 허용 (주문자 전화번호 자동)", Color.parseColor("#6B4FA0")) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 11)
        })
        root.addView(btn("4. 자동 업데이트 허용 (이 앱에서 설치)", Color.parseColor("#2F6F4E")) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        })
        root.addView(btn("플로팅 버튼 켜기", Color.parseColor("#2F6F4E")) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "먼저 '다른 앱 위에 표시'를 허용해주세요", Toast.LENGTH_LONG).show()
            } else {
                startBubble()
                Toast.makeText(this, "플로팅 버튼을 켰어요", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(btn("플로팅 버튼 끄기", Color.parseColor("#5F5E5A")) {
            stopService(Intent(this, BubbleService::class.java))
        })

        // ---------- 배송 알림 ----------
        val dlvTitle = TextView(this)
        dlvTitle.text = "📦 배송 알림 (앱을 닫아 둬도)"
        dlvTitle.textSize = 18f
        dlvTitle.setTextColor(Color.parseColor("#1B2430"))
        dlvTitle.setPadding(0, 40, 0, 0)
        root.addView(dlvTitle)
        val dlvDesc = TextView(this)
        dlvDesc.text = "우체국 택배를 보낸 다음 날 12시·16시에 배송중/배송완료를 알려 주고,\n그다음 날 9시에 아직 도착 안 된 것만 다시 알려 줘요.\n(주문관리 앱에 송장번호가 들어간 '발송완료' 주문 기준)"
        dlvDesc.textSize = 13f
        dlvDesc.setTextColor(Color.parseColor("#5F5E5A"))
        dlvDesc.setPadding(0, 8, 0, 0)
        root.addView(dlvDesc)
        val dlvStatus = TextView(this)
        dlvStatus.textSize = 13f
        dlvStatus.setPadding(0, 12, 0, 0)
        fun showDlv() {
            dlvStatus.text = "다음 알림: " + DeliveryCheck.nextText(this) +
                (if (DeliveryCheck.canExact(this)) "  (정확한 시간 ✓)" else "  (정확한 시간 허용 필요 ✗)") +
                (if (DeliveryCheck.lastText(this).isNotEmpty()) "\n최근 확인: " + DeliveryCheck.lastText(this) else "")
        }
        showDlv()
        root.addView(dlvStatus)
        // v1.9: 보안 로그인 (주문관리 앱과 같은 아이디·비밀번호)
        val loginInfo = TextView(this)
        loginInfo.textSize = 14f
        loginInfo.setPadding(0, 16, 0, 0)
        fun showLogin() {
            loginInfo.text = DeliveryCheck.loginText(this)
            loginInfo.setTextColor(if (DeliveryCheck.loginText(this).startsWith("로그인:")) Color.parseColor("#2F6F4E") else Color.parseColor("#C0392B"))
        }
        showLogin()
        root.addView(loginInfo)
        val idEdit = EditText(this)
        idEdit.hint = "주문관리 아이디"
        idEdit.isSingleLine = true
        root.addView(idEdit)
        val pwEdit = EditText(this)
        pwEdit.hint = "비밀번호"
        pwEdit.isSingleLine = true
        pwEdit.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        root.addView(pwEdit)
        root.addView(btn("로그인 (주문관리와 같은 아이디)", Color.parseColor("#2F6F4E")) {
            val id = idEdit.text.toString()
            val pw = pwEdit.text.toString()
            loginInfo.text = "로그인 중…"
            Thread {
                val r = try { DeliveryCheck.login(this, id, pw) } catch (e: Exception) { "로그인 실패: " + e.message }
                runOnUiThread {
                    showLogin()
                    pwEdit.setText("")
                    Toast.makeText(this, if (r.isEmpty()) "로그인했어요" else r, Toast.LENGTH_LONG).show()
                }
            }.start()
        })
        root.addView(btn("지금 배송 확인해 보기 (시험 알림)", Color.parseColor("#2F6F4E")) {
            dlvStatus.text = "확인 중…"
            Thread {
                val r = try { DeliveryCheck.run(this, -1, true) } catch (e: Exception) { "확인 실패: " + e.message }
                runOnUiThread { showDlv(); Toast.makeText(this, r.lineSequence().firstOrNull() ?: r, Toast.LENGTH_LONG).show() }
            }.start()
        })
        if (Build.VERSION.SDK_INT >= 31) {
            root.addView(btn("정확한 시간 알림 허용", Color.parseColor("#185FA5")) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    Toast.makeText(this, "설정 → 앱 → 조아저씨쌈 플로팅 → 알람 및 리마인더를 허용해 주세요", Toast.LENGTH_LONG).show()
                }
            })
        }

        val updText = TextView(this)
        updText.textSize = 14f
        updText.setPadding(0, 40, 0, 0)
        updText.text = "현재 버전: 빌드 " + Updater.currentCode(this) + "  (새 버전은 자동으로 확인해요)"
        root.addView(updText)
        root.addView(btn("지금 업데이트 확인", Color.parseColor("#185FA5")) {
            if (!Updater.canInstall(this)) {
                Toast.makeText(this, "'이 앱에서 설치 허용'을 켜 주세요", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } else {
                updText.text = "확인 중…"
                Updater.checkAndDownload(this, true) { m ->
                    runOnUiThread {
                        updText.text = m
                        if (Updater.readyCode(this) > 0) {
                            if (Updater.installIfReady(this)) updText.text = m + "\n설치를 시작했어요. 확인 창이 뜨면 '설치'를 눌러 주세요"
                        }
                    }
                }
            }
        })

        val urlLabel = TextView(this)
        urlLabel.text = "주문관리 앱 주소"
        urlLabel.setPadding(0, 40, 0, 0)
        root.addView(urlLabel)
        val urlEdit = EditText(this)
        urlEdit.setText(Common.baseUrl(this))
        urlEdit.isSingleLine = true
        root.addView(urlEdit)
        root.addView(btn("주소 저장", Color.parseColor("#5F5E5A")) {
            Common.setBaseUrl(this, urlEdit.text.toString())
            Toast.makeText(this, "저장했어요", Toast.LENGTH_SHORT).show()
        })

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        refreshStatus()
    }

    private fun refreshStatus() {
        val overlay = Settings.canDrawOverlays(this)
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        status.text = "다른 앱 위에 표시: " + (if (overlay) "허용됨 ✓" else "필요 ✗") +
            "\n알림: " + (if (notif) "허용됨 ✓" else "필요 ✗") +
            "\n연락처: " + (if (contacts) "허용됨 ✓" else "선택 (허용하면 주문자 번호 자동 입력)") +
            "\n자동 업데이트: " + (if (Updater.canInstall(this)) "허용됨 ✓" else "선택 (허용하면 새 버전이 저절로 설치돼요)")
        status.setTextColor(if (overlay && notif) Color.parseColor("#2F6F4E") else Color.parseColor("#C0392B"))
        status.gravity = Gravity.START
    }
}
