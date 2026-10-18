package com.joassam.floating

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 정해진 시간(9·12·16시) 알람 + 폰 재부팅·앱 업데이트 뒤 알람 다시 예약 */
class DeliveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.action != DeliveryCheck.ACTION) {
            DeliveryCheck.schedule(app)
            return
        }
        val hour = intent.getIntExtra("hour", -1)
        val pending = goAsync()
        Thread {
            try {
                if (hour >= 0) DeliveryCheck.run(app, hour, false)
            } catch (e: Exception) {
            } finally {
                try { DeliveryCheck.schedule(app) } catch (e: Exception) {}
                pending.finish()
            }
        }.start()
    }
}
