package com.example.ai_vision.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.ai_vision.MainActivity

/** Keeps the local BLE/hotspot/TCP runtime visible and alive when the screen closes. */
class GlassesConnectionService : Service() {
    private var epoch = -1L
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Kết nối kính", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Ai-Vision đang kết nối kính")
            .setContentText("Xử lý giọng nói local; chạm để mở app")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Ngắt kết nối", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(42, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.hasExtra("epoch") == true) epoch = intent.getLongExtra("epoch", -1L)
        if (intent?.action == STOP) {
            GlassesRuntime.controller(application).disconnect()
            stopSelf()
        }
        // Do not silently restore/replay commands after Android kills this process.
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        GlassesRuntime.controller(application).serviceDestroyed(epoch)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "glasses_connection"
        private const val STOP = "com.example.ai_vision.STOP_CONNECTION"
        fun start(context: Context, epoch: Long) { ContextCompat.startForegroundService(context, Intent(context, GlassesConnectionService::class.java).putExtra("epoch", epoch)) }
        fun stop(context: Context) { context.stopService(Intent(context, GlassesConnectionService::class.java)) }
    }
}
