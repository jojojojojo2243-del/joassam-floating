package com.joassam.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build

object Common {
    const val DEFAULT_URL = "https://joassam-app.vercel.app"
    private const val PREFS = "joassam_floating"
    private const val CHANNEL_ID = "floating"

    fun baseUrl(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("base_url", DEFAULT_URL)!!.trimEnd('/')

    fun setBaseUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("base_url", url.trim().trimEnd('/')).apply()
    }

    fun notification(ctx: Context, text: String): Notification {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "플로팅 버튼", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(ctx, CHANNEL_ID)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_input_add)
            .setOngoing(true)
            .build()
    }

    /** v3.0: 플로팅에서 읽은 주문 문자를 이 앱(주문관리 화면)의 주문 등록 창으로 넘김 */
    fun openOrderApp(ctx: Context, text: String, sender: String, fromOcr: Boolean = false, senderPhone: String = "") {
        val i = Intent(ctx, MainActivity::class.java)
        i.putExtra(MainActivity.EXTRA_TEXT, text)
        i.putExtra(MainActivity.EXTRA_SENDER, sender)
        i.putExtra(MainActivity.EXTRA_PHONE, senderPhone)
        i.putExtra(MainActivity.EXTRA_OCR, fromOcr)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        ctx.startActivity(i)
    }
}
