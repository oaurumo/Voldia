package com.oaurumo.voldia

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.oaurumo.voldia.databinding.ActivityMainBinding

/**
 * 主界面：权限引导、启动/停止悬浮球、呼出音量卡片与系统音量面板，
 * 以及通过 Shizuku 执行关机/重启。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            updateUi()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            updateUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.btnGrant.setOnClickListener { openOverlaySettings() }
        binding.btnStart.setOnClickListener { startFloating() }
        binding.btnStop.setOnClickListener {
            FloatWindowService.stop(this)
            Toast.makeText(this, R.string.service_stopped, Toast.LENGTH_SHORT).show()
            binding.root.postDelayed({ updateUi() }, 600L)
        }
        binding.btnShowCard.setOnClickListener {
            if (!hasOverlayPermission()) {
                openOverlaySettings()
                return@setOnClickListener
            }
            FloatWindowService.showCard(this)
            Toast.makeText(this, R.string.card_shown, Toast.LENGTH_SHORT).show()
            binding.root.postDelayed({ updateUi() }, 600L)
        }
        binding.btnShowPanel.setOnClickListener {
            if (!VolumeController.showSystemPanel(this)) {
                Toast.makeText(this, R.string.volume_panel_failed, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnShutdown.setOnClickListener {
            showConfirmDialog(
                R.string.confirm_shutdown_title,
                R.string.confirm_shutdown_message
            ) { ensureShizukuOrRun { executePowerCommand(arrayOf("svc", "power", "shutdown")) } }
        }
        binding.btnReboot.setOnClickListener {
            showConfirmDialog(
                R.string.confirm_reboot_title,
                R.string.confirm_reboot_message
            ) { ensureShizukuOrRun { executePowerCommand(arrayOf("svc", "power", "reboot")) } }
        }

        setupAppearanceControls()
        requestNotificationPermissionIfNeeded()
    }

    private fun showConfirmDialog(title: Int, message: Int, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _: DialogInterface, _: Int -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 检查 Shizuku 服务与授权：已授权则立即执行，否则发起授权请求并暂存动作。 */
    private fun ensureShizukuOrRun(action: () -> Unit) {
        PowerController.ensureShizukuAndRun(this, action) {
            Toast.makeText(this, R.string.shizuku_not_running, Toast.LENGTH_LONG).show()
        }
    }

    /** 通过 Shizuku 在后台线程执行 shell 命令（关机/重启）。 */
    private fun executePowerCommand(cmd: Array<String>) {
        PowerController.execute(cmd) { err ->
            Toast.makeText(this, getString(R.string.shizuku_exec_failed, err), Toast.LENGTH_LONG).show()
        }
    }

    private fun setupAppearanceControls() {
        binding.seekSize.max = BallPrefs.MAX_SIZE_DP - BallPrefs.MIN_SIZE_DP
        binding.seekOpacity.max = BallPrefs.MAX_OPACITY - BallPrefs.MIN_OPACITY
        binding.seekCardWidth.max = BallPrefs.MAX_CARD_WIDTH_PERCENT - BallPrefs.MIN_CARD_WIDTH_PERCENT

        binding.seekSize.setOnSeekBarChangeListener(object : SeekBarListener() {
            override fun onChanged(progress: Int, fromUser: Boolean) {
                val size = BallPrefs.MIN_SIZE_DP + progress
                BallPrefs.setSizeDp(this@MainActivity, size)
                binding.tvSizeValue.text = getString(R.string.appearance_size_value, size)
                if (fromUser) FloatWindowService.applyAppearance(this@MainActivity)
            }
        })
        binding.seekOpacity.setOnSeekBarChangeListener(object : SeekBarListener() {
            override fun onChanged(progress: Int, fromUser: Boolean) {
                val opacity = BallPrefs.MIN_OPACITY + progress
                BallPrefs.setOpacity(this@MainActivity, opacity)
                binding.tvOpacityValue.text = getString(R.string.appearance_opacity_value, opacity)
                if (fromUser) FloatWindowService.applyAppearance(this@MainActivity)
            }
        })
        binding.seekCardWidth.setOnSeekBarChangeListener(object : SeekBarListener() {
            override fun onChanged(progress: Int, fromUser: Boolean) {
                val percent = BallPrefs.MIN_CARD_WIDTH_PERCENT + progress
                BallPrefs.setCardWidthPercent(this@MainActivity, percent)
                binding.tvCardWidthValue.text = getString(R.string.appearance_card_width_value, percent)
                if (fromUser) FloatWindowService.applyCardAppearance(this@MainActivity)
            }
        })

        // 微调按钮：每次步进 1，驱动 SeekBar（复用其监听完成持久化），并实时应用
        binding.btnSizeMinus.setOnClickListener {
            binding.seekSize.progress = (binding.seekSize.progress - 1).coerceAtLeast(0)
            FloatWindowService.applyAppearance(this)
        }
        binding.btnSizePlus.setOnClickListener {
            binding.seekSize.progress = (binding.seekSize.progress + 1).coerceAtMost(binding.seekSize.max)
            FloatWindowService.applyAppearance(this)
        }
        binding.btnOpacityMinus.setOnClickListener {
            binding.seekOpacity.progress = (binding.seekOpacity.progress - 1).coerceAtLeast(0)
            FloatWindowService.applyAppearance(this)
        }
        binding.btnOpacityPlus.setOnClickListener {
            binding.seekOpacity.progress = (binding.seekOpacity.progress + 1).coerceAtMost(binding.seekOpacity.max)
            FloatWindowService.applyAppearance(this)
        }
        binding.btnCardWidthMinus.setOnClickListener {
            binding.seekCardWidth.progress = (binding.seekCardWidth.progress - 1).coerceAtLeast(0)
            FloatWindowService.applyCardAppearance(this)
        }
        binding.btnCardWidthPlus.setOnClickListener {
            binding.seekCardWidth.progress = (binding.seekCardWidth.progress + 1).coerceAtMost(binding.seekCardWidth.max)
            FloatWindowService.applyCardAppearance(this)
        }

        syncAppearanceControls()
    }

    private fun syncAppearanceControls() {
        val size = BallPrefs.getSizeDp(this)
        val opacity = BallPrefs.getOpacity(this)
        val cardWidth = BallPrefs.getCardWidthPercent(this)
        binding.seekSize.progress = size - BallPrefs.MIN_SIZE_DP
        binding.seekOpacity.progress = opacity - BallPrefs.MIN_OPACITY
        binding.seekCardWidth.progress = cardWidth - BallPrefs.MIN_CARD_WIDTH_PERCENT
        binding.tvSizeValue.text = getString(R.string.appearance_size_value, size)
        binding.tvOpacityValue.text = getString(R.string.appearance_opacity_value, opacity)
        binding.tvCardWidthValue.text = getString(R.string.appearance_card_width_value, cardWidth)
    }

    private abstract class SeekBarListener : android.widget.SeekBar.OnSeekBarChangeListener {
        abstract fun onChanged(progress: Int, fromUser: Boolean)
        override fun onProgressChanged(
            seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean
        ) = onChanged(progress, fromUser)

        override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
    }

    override fun onResume() {
        super.onResume()
        updateUi()
        syncAppearanceControls()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun startFloating() {
        if (!hasOverlayPermission()) {
            openOverlaySettings()
            return
        }
        FloatWindowService.start(this)
        Toast.makeText(this, R.string.service_started, Toast.LENGTH_SHORT).show()
        binding.root.postDelayed({ updateUi() }, 600L)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasOverlayPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun updateUi() {
        val granted = hasOverlayPermission()
        val running = FloatWindowService.isRunning

        binding.tvPermissionStatus.setText(
            if (granted) R.string.permission_granted else R.string.permission_denied
        )
        binding.btnGrant.isEnabled = !granted
        binding.btnStart.isEnabled = granted && !running
        binding.btnStop.isEnabled = running
        binding.btnShowCard.isEnabled = granted
    }
}
