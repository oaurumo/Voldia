package com.oaurumo.voldia

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 悬浮球 + 悬浮音量卡片的前台服务。
 *
 * 交互：
 * - 单击悬浮球：呼出系统音量面板
 * - 长按悬浮球：展开 / 收起音量卡片
 * - 拖动悬浮球：移动位置并自动吸附屏幕边缘
 * - 音量卡片：拖动滑块实时调节媒体音量，点击卡片空白处或“系统音量面板”呼出系统面板
 */
class FloatWindowService : Service() {

    companion object {
        private const val TAG = "FloatWindowService"
        private const val CHANNEL_ID = "voldia_floating"
        private const val NOTIFICATION_ID = 1001
        private const val CARD_AUTO_CLOSE_DELAY = 5000L

        const val ACTION_START = "com.oaurumo.voldia.action.START"
        const val ACTION_STOP = "com.oaurumo.voldia.action.STOP"
        const val ACTION_SHOW_CARD = "com.oaurumo.voldia.action.SHOW_CARD"
        const val ACTION_TOGGLE_CARD = "com.oaurumo.voldia.action.TOGGLE_CARD"
        const val ACTION_UPDATE_BALL = "com.oaurumo.voldia.action.UPDATE_BALL"
        const val ACTION_UPDATE_CARD = "com.oaurumo.voldia.action.UPDATE_CARD"
        const val EXTRA_SHOW_CARD = "extra_show_card"

        /** 主界面调整外观时调用：把最新尺寸/透明度应用到悬浮球。 */
        fun applyAppearance(context: Context) {
            val intent = Intent(context, FloatWindowService::class.java).apply {
                action = ACTION_UPDATE_BALL
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 主界面调整卡片宽度时调用：实时更新音量卡片宽度。 */
        fun applyCardAppearance(context: Context) {
            val intent = Intent(context, FloatWindowService::class.java).apply {
                action = ACTION_UPDATE_CARD
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context, showCard: Boolean = false) {
            val intent = Intent(context, FloatWindowService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SHOW_CARD, showCard)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun showCard(context: Context) {
            val intent = Intent(context, FloatWindowService::class.java).apply {
                action = ACTION_SHOW_CARD
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatWindowService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager

    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null

    private var cardView: View? = null
    private var cardParams: WindowManager.LayoutParams? = null
    private var seekBar: SeekBar? = null
    private var percentView: TextView? = null
    private var isSeeking = false

    // 电源确认对话框（悬浮窗）
    private var powerDialogView: View? = null

    // 卡片拖动状态
    private var cardStartX = 0
    private var cardStartY = 0
    private var cardTouchX = 0f
    private var cardTouchY = 0f
    private var cardIsDragging = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private var startX = 0
    private var startY = 0
    private var touchX = 0f
    private var touchY = 0f
    private var isDragging = false
    private var longPressTriggered = false

    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private val longPressRunnable = Runnable {
        longPressTriggered = true
        ballView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        toggleCard()
    }

    private val volumePoll = object : Runnable {
        override fun run() {
            updateVolumeUi()
            if (cardView != null) {
                mainHandler.postDelayed(this, 500L)
            }
        }
    }

    /** 卡片无操作超过 [CARD_AUTO_CLOSE_DELAY] 后自动关闭。 */
    private val cardAutoClose = Runnable { hideCard() }

    private fun resetCardAutoClose() {
        mainHandler.removeCallbacks(cardAutoClose)
        if (cardView != null) {
            mainHandler.postDelayed(cardAutoClose, CARD_AUTO_CLOSE_DELAY)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_SHOW_CARD -> {
                if (!enterForeground()) {
                    shutdown()
                    return START_NOT_STICKY
                }
                ensureBall()
                showCard()
            }

            ACTION_TOGGLE_CARD -> {
                if (!enterForeground()) {
                    shutdown()
                    return START_NOT_STICKY
                }
                ensureBall()
                toggleCard()
            }

            ACTION_UPDATE_BALL -> {
                ensureBall()
                applyBallAppearance()
            }

            ACTION_UPDATE_CARD -> {
                applyCardWidth()
            }

            else -> {
                if (!enterForeground()) {
                    shutdown()
                    return START_NOT_STICKY
                }
                ensureBall()
                if (intent?.getBooleanExtra(EXTRA_SHOW_CARD, false) == true) {
                    showCard()
                }
            }
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------- 前台通知

    private fun enterForeground(): Boolean {
        createNotificationChannel()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(),
                    0
                )
            }
            isRunning = true
            true
        } catch (e: Exception) {
            isRunning = false
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cardIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, FloatWindowService::class.java).setAction(ACTION_SHOW_CARD),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, FloatWindowService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_volume)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.notification_action_card), cardIntent)
            .addAction(0, getString(R.string.notification_action_stop), stopIntent)
            .build()
    }

    // ---------------------------------------------------------------- 悬浮球

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun ensureBall() {
        if (ballView != null) return
        if (!canDrawOverlays()) {
            Toast.makeText(this, R.string.overlay_permission_missing, Toast.LENGTH_SHORT).show()
            shutdown()
            return
        }

        val size = dp(BallPrefs.getSizeDp(this))
        val view = LayoutInflater.from(this).inflate(R.layout.floating_ball, null)
        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val screen = screenSize()
            x = screen.x - size - dp(8)
            y = screen.y / 2 - size / 2
        }

        view.setOnTouchListener { v, event -> handleBallTouch(v, event, params) }

        try {
            windowManager.addView(view, params)
            ballView = view
            ballParams = params
            applyBallAppearance()
        } catch (e: Exception) {
            toast(R.string.overlay_add_failed)
            shutdown()
        }
    }

    /** 把 [BallPrefs] 中最新的尺寸与透明度应用到悬浮球，并保证仍在屏幕内。 */
    private fun applyBallAppearance() {
        val view = ballView ?: return
        val params = ballParams ?: return

        val size = dp(BallPrefs.getSizeDp(this))
        val opacity = BallPrefs.getOpacity(this) / 100f

        val oldW = view.width.takeIf { it > 0 } ?: params.width
        val oldH = view.height.takeIf { it > 0 } ?: params.height

        params.width = size
        params.height = size
        // 以中心为基准重新定位，避免尺寸变化时悬浮球"漂移"
        val centerX = params.x + oldW / 2
        val centerY = params.y + oldH / 2
        params.x = centerX - size / 2
        params.y = centerY - size / 2

        val screen = screenSize()
        params.x = params.x.coerceIn(0, max(0, screen.x - size))
        params.y = params.y.coerceIn(0, max(0, screen.y - size))

        view.alpha = opacity
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /** 卡片宽度（像素）= 屏幕宽度 × 设置的百分比。 */
    private fun cardWidthPx(): Int {
        val percent = BallPrefs.getCardWidthPercent(this)
        return screenSize().x * percent / 100
    }

    /** 实时更新音量卡片宽度（高度自适应），并把卡片限制在屏幕内。 */
    private fun applyCardWidth() {
        val view = cardView ?: return
        val params = cardParams ?: return
        val newWidth = cardWidthPx()
        params.width = newWidth
        val screen = screenSize()
        params.x = params.x.coerceIn(0, max(0, screen.x - newWidth))
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun handleBallTouch(view: View, event: MotionEvent, params: WindowManager.LayoutParams): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = params.x
                startY = params.y
                touchX = event.rawX
                touchY = event.rawY
                isDragging = false
                longPressTriggered = false
                mainHandler.removeCallbacks(longPressRunnable)
                mainHandler.postDelayed(longPressRunnable, 500L)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchX
                val dy = event.rawY - touchY
                if (!isDragging && sqrt(dx * dx + dy * dy) > touchSlop) {
                    isDragging = true
                    mainHandler.removeCallbacks(longPressRunnable)
                }
                if (isDragging) {
                    val screen = screenSize()
                    val w = view.width.takeIf { it > 0 } ?: dp(BallPrefs.getSizeDp(this@FloatWindowService))
                    val h = view.height.takeIf { it > 0 } ?: dp(BallPrefs.getSizeDp(this@FloatWindowService))
                    params.x = (startX + dx.toInt()).coerceIn(0, max(0, screen.x - w))
                    params.y = (startY + dy.toInt()).coerceIn(0, max(0, screen.y - h))
                    runCatching { windowManager.updateViewLayout(view, params) }
                }
            }

            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressRunnable)
                when {
                    isDragging -> {
                        snapBallToEdge(view, params)
                    }
                    !longPressTriggered -> showSystemPanel()
                }
                isDragging = false
                longPressTriggered = false
            }

            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                isDragging = false
                longPressTriggered = false
            }
        }
        return true
    }

    private fun snapBallToEdge(view: View, params: WindowManager.LayoutParams) {
        val screen = screenSize()
        val w = view.width.takeIf { it > 0 } ?: dp(BallPrefs.getSizeDp(this@FloatWindowService))
        val h = view.height.takeIf { it > 0 } ?: dp(BallPrefs.getSizeDp(this@FloatWindowService))
        params.x = if (params.x + w / 2 < screen.x / 2) {
            dp(4)
        } else {
            max(dp(4), screen.x - w - dp(4))
        }
        params.y = params.y.coerceIn(0, max(0, screen.y - h))
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    // ---------------------------------------------------------------- 音量卡片

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun showCard() {
        if (!canDrawOverlays()) {
            toast(R.string.overlay_permission_missing)
            return
        }
        if (cardView != null) {
            updateVolumeUi()
            resetCardAutoClose()
            return
        }

        val view = LayoutInflater.from(this).inflate(R.layout.floating_card, null)
        val params = WindowManager.LayoutParams(
            cardWidthPx(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val percent = view.findViewById<TextView>(R.id.tv_percent)
        val bar = view.findViewById<SeekBar>(R.id.seek_volume)

        val max = VolumeController.maxVolume(this)
        bar.max = max
        bar.progress = VolumeController.currentVolume(this)
        percent.text = getString(R.string.volume_percent, VolumeController.percent(this))

        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                VolumeController.setMediaVolume(this@FloatWindowService, progress)
                val pct = (progress * 100 + max / 2) / max
                percent.text = getString(R.string.volume_percent, pct)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
                mainHandler.removeCallbacks(cardAutoClose)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                updateVolumeUi()
                resetCardAutoClose()
            }
        })

        // 点击卡片空白区域呼出系统音量面板；标题栏作为拖动手柄（单击呼出面板，拖动自由移动卡片）
        view.setOnClickListener { showSystemPanel(); resetCardAutoClose() }
        view.findViewById<View>(R.id.btn_panel).setOnClickListener { showSystemPanel(); resetCardAutoClose() }
        view.findViewById<View>(R.id.btn_close).setOnClickListener { hideCard() }
        view.findViewById<View>(R.id.btn_power_off).setOnClickListener {
            resetCardAutoClose()
            showPowerConfirmDialog(reboot = false)
        }
        view.findViewById<View>(R.id.btn_reboot).setOnClickListener {
            resetCardAutoClose()
            showPowerConfirmDialog(reboot = true)
        }
        view.findViewById<View>(R.id.card_header).setOnTouchListener { _, event ->
            handleCardTouch(event, params)
        }

        view.alpha = 0f
        view.scaleX = 0.92f
        view.scaleY = 0.92f
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            toast(R.string.overlay_add_failed)
            return
        }

        cardView = view
        cardParams = params
        seekBar = bar
        percentView = percent

        view.post {
            positionCard()
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180L)
                .start()
        }

        mainHandler.removeCallbacks(volumePoll)
        mainHandler.postDelayed(volumePoll, 500L)
        resetCardAutoClose()
    }

    private fun hideCard() {
        mainHandler.removeCallbacks(volumePoll)
        mainHandler.removeCallbacks(cardAutoClose)
        dismissPowerDialog()
        cardView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        cardView = null
        cardParams = null
        seekBar = null
        percentView = null
        isSeeking = false
    }

    private fun toggleCard() {
        if (cardView != null) hideCard() else showCard()
    }

    /** 显示关机/重启确认对话框（悬浮窗，避免后台启动 Activity 的限制）。 */
    private fun showPowerConfirmDialog(reboot: Boolean) {
        // 已存在则先移除
        dismissPowerDialog()

        val view = LayoutInflater.from(this).inflate(R.layout.floating_power_dialog, null)
        val titleRes = if (reboot) R.string.confirm_reboot_title else R.string.confirm_shutdown_title
        val msgRes = if (reboot) R.string.confirm_reboot_message else R.string.confirm_shutdown_message
        view.findViewById<TextView>(R.id.power_dialog_title).setText(titleRes)
        view.findViewById<TextView>(R.id.power_dialog_message).setText(msgRes)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        view.findViewById<View>(R.id.power_dialog_cancel).setOnClickListener { dismissPowerDialog() }
        view.findViewById<View>(R.id.power_dialog_confirm).setOnClickListener {
            val cmd = if (reboot) arrayOf("svc", "power", "reboot")
            else arrayOf("svc", "power", "shutdown")
            dismissPowerDialog()
            PowerController.ensureShizukuAndRun(this, {
                PowerController.execute(cmd) { err ->
                    toast(getString(R.string.shizuku_exec_failed, err))
                }
            }) {
                toast(R.string.shizuku_not_running)
            }
        }

        try {
            windowManager.addView(view, params)
            powerDialogView = view
        } catch (e: Exception) {
            toast(R.string.overlay_add_failed)
        }
    }

    private fun dismissPowerDialog() {
        powerDialogView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        powerDialogView = null
    }

    private fun positionCard() {
        val view = cardView ?: return
        val params = cardParams ?: return
        val ball = ballParams ?: return

        val screen = screenSize()
        val cardWidth = view.width.takeIf { it > 0 } ?: cardWidthPx()
        val cardHeight = view.height.takeIf { it > 0 } ?: dp(140)
        val ballSize = ballView?.width?.takeIf { it > 0 } ?: dp(56)
        val gap = dp(10)

        var x = ball.x + ballSize + gap
        if (x + cardWidth > screen.x - dp(8)) {
            x = ball.x - cardWidth - gap
        }
        x = x.coerceIn(dp(8), max(dp(8), screen.x - cardWidth - dp(8)))

        var y = ball.y + ballSize / 2 - cardHeight / 2
        y = y.coerceIn(dp(8), max(dp(8), screen.y - cardHeight - dp(8)))

        params.x = x
        params.y = y
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /**
     * 卡片标题栏触摸：单击呼出系统音量面板，拖动则自由移动卡片。
     * 拖动过程中暂停自动关闭，松手后恢复计时。
     */
    private fun handleCardTouch(event: MotionEvent, params: WindowManager.LayoutParams): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cardStartX = params.x
                cardStartY = params.y
                cardTouchX = event.rawX
                cardTouchY = event.rawY
                cardIsDragging = false
                mainHandler.removeCallbacks(cardAutoClose)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - cardTouchX
                val dy = event.rawY - cardTouchY
                if (!cardIsDragging && sqrt(dx * dx + dy * dy) > touchSlop) {
                    cardIsDragging = true
                }
                if (cardIsDragging) {
                    val screen = screenSize()
                    val view = cardView
                    val w = view?.width?.takeIf { it > 0 } ?: cardWidthPx()
                    val h = view?.height?.takeIf { it > 0 } ?: dp(140)
                    params.x = (cardStartX + dx.toInt()).coerceIn(0, max(0, screen.x - w))
                    params.y = (cardStartY + dy.toInt()).coerceIn(0, max(0, screen.y - h))
                    runCatching { windowManager.updateViewLayout(view ?: return false, params) }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!cardIsDragging) {
                    showSystemPanel()
                }
                resetCardAutoClose()
                cardIsDragging = false
            }

            MotionEvent.ACTION_CANCEL -> {
                resetCardAutoClose()
                cardIsDragging = false
            }
        }
        return true
    }

    private fun updateVolumeUi() {
        if (cardView == null) return
        val percent = percentView ?: return
        val current = VolumeController.currentVolume(this)
        val max = VolumeController.maxVolume(this)
        seekBar?.max = max
        if (!isSeeking) {
            seekBar?.progress = current
        }
        percent.text = getString(R.string.volume_percent, (current * 100 + max / 2) / max)
    }

    // ---------------------------------------------------------------- 通用

    private fun showSystemPanel() {
        if (!VolumeController.showSystemPanel(this)) {
            toast(R.string.volume_panel_failed)
        }
    }

    private fun canDrawOverlays(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getSize(point)
            point
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun shutdown() {
        hideCard()
        ballView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        ballView = null
        ballParams = null
        mainHandler.removeCallbacksAndMessages(null)
        isRunning = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        hideCard()
        ballView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        ballView = null
        ballParams = null
        mainHandler.removeCallbacksAndMessages(null)
        isRunning = false
        super.onDestroy()
    }

    /** 旋转屏幕后把悬浮球和卡片限制在新的屏幕范围内 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ballView?.let { view ->
            ballParams?.let { params ->
                val screen = screenSize()
                val w = view.width.takeIf { it > 0 } ?: dp(BallPrefs.getSizeDp(this@FloatWindowService))
                val h = view.height.takeIf { it > 0 } ?: dp(BallPrefs.getSizeDp(this@FloatWindowService))
                params.x = params.x.coerceIn(0, max(0, screen.x - w))
                params.y = params.y.coerceIn(0, max(0, screen.y - h))
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }
        // 旋转后仅把卡片限制在屏幕范围内，不改变其相对位置
        cardView?.let { view ->
            cardParams?.let { params ->
                val screen = screenSize()
                val w = view.width.takeIf { it > 0 } ?: cardWidthPx()
                val h = view.height.takeIf { it > 0 } ?: dp(140)
                params.x = params.x.coerceIn(0, max(0, screen.x - w))
                params.y = params.y.coerceIn(0, max(0, screen.y - h))
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }
    }
}
