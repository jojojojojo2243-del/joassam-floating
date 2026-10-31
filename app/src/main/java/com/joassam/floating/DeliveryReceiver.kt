package com.joassam.floating

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 정해진 시간(9·12·16시) 알람 + 폰 재부팅·앱 업데이트 뒤 알람 다시 예약 */
class DeliveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.action == Updater.ACTION_UPDATE) {
            // v3.1: 새 버전 자동 확인·설치 (2시간마다)
            val pend = goAsync()
            Thread {
                try { Updater.autoRun(app) } catch (e: Exception) {
                } finally {
                    try { Updater.scheduleAuto(app, true) } catch (e: Exception) {}
                    pend.finish()
                }
            }.start()
            return
        }
        if (intent.action != DeliveryCheck.ACTION) {
            DeliveryCheck.schedule(app)
            try { Updater.scheduleAuto(app, true) } catch (e: Exception) {}
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
