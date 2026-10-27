package com.usagi.editorvideo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano que mantém o processo vivo durante a exportação e mostra o progresso
 * numa notificação. Quem exporta de verdade é o [ExportManager]; o serviço só observa o estado.
 */
class ExportService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            ExportManager.cancel()
            return START_NOT_STICKY
        }

        createChannel()
        val percent = (ExportManager.state.value as? ExportState.Running)?.percent ?: 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, progressNotification(percent), foregroundType())
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        job?.cancel()
        job = scope.launch {
            ExportManager.state.collect { s ->
                when (s) {
                    is ExportState.Running -> notificationManager().notify(NOTIF_ID, progressNotification(s.percent))
                    is ExportState.Done -> finish("Vídeo exportado", s.message)
                    is ExportState.Failed -> finish("Falha na exportação", s.message)
                    ExportState.Idle -> finish(null, null)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun finish(title: String?, text: String?) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (title != null) notificationManager().notify(RESULT_ID, resultNotification(title, text.orEmpty()))
        stopSelf()
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Exportação de vídeo", NotificationManager.IMPORTANCE_LOW)
        notificationManager().createNotificationChannel(channel)
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun progressNotification(percent: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Exportando vídeo")
            .setContentText("$percent%")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(
                0, "Cancelar",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

    private fun resultNotification(title: String, text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()

    companion object {
        private const val CHANNEL_ID = "export"
        private const val NOTIF_ID = 1
        private const val RESULT_ID = 2
        private const val ACTION_CANCEL = "com.usagi.editorvideo.CANCEL_EXPORT"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java))
            } catch (_: Exception) {
                // sem o serviço a exportação continua, só não fica protegida em segundo plano
            }
        }
    }
}
