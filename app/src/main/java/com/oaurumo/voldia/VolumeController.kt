package com.oaurumo.voldia

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * 系统音量控制工具。
 *
 * 呼出系统音量面板的关键：调用 AudioManager 的 adjustStreamVolume/setStreamVolume 时
 * 携带 AudioManager.FLAG_SHOW_UI，SystemUI 就会显示系统音量对话框。
 * 这里优先使用 ADJUST_SAME（不改变音量）的方式，避免每次点击都改变音量。
 */
object VolumeController {

    private const val TAG = "VolumeController"

    /** 统一使用媒体音量流 */
    const val STREAM_TYPE = AudioManager.STREAM_MUSIC

    private fun audioManager(context: Context): AudioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun maxVolume(context: Context): Int =
        audioManager(context).getStreamMaxVolume(STREAM_TYPE).coerceAtLeast(1)

    fun currentVolume(context: Context): Int {
        val max = maxVolume(context)
        return audioManager(context).getStreamVolume(STREAM_TYPE).coerceIn(0, max)
    }

    fun percent(context: Context): Int {
        val max = maxVolume(context)
        return (currentVolume(context) * 100 + max / 2) / max
    }

    /** 直接设置媒体音量（滑块拖动时使用，不弹系统面板） */
    fun setMediaVolume(context: Context, index: Int) {
        val am = audioManager(context)
        val max = am.getStreamMaxVolume(STREAM_TYPE).coerceAtLeast(1)
        runCatching {
            am.setStreamVolume(STREAM_TYPE, index.coerceIn(0, max), 0)
        }.onFailure { Log.w(TAG, "setStreamVolume 失败", it) }
    }

    /**
     * 呼出系统音量面板。
     * @return 是否成功调用了系统接口
     */
    fun showSystemPanel(context: Context): Boolean {
        val am = audioManager(context)
        val current = am.getStreamVolume(STREAM_TYPE)
        val max = am.getStreamMaxVolume(STREAM_TYPE)

        // 方案 1（首选）：ADJUST_SAME 不改变音量，FLAG_SHOW_UI 触发系统音量面板
        try {
            am.adjustStreamVolume(
                STREAM_TYPE,
                AudioManager.ADJUST_SAME,
                AudioManager.FLAG_SHOW_UI
            )
            Log.d(TAG, "adjustStreamVolume(ADJUST_SAME, FLAG_SHOW_UI) 成功")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "方案 1 失败", e)
        }

        // 方案 2：以当前音量重新设置一次，并携带 FLAG_SHOW_UI
        try {
            am.setStreamVolume(STREAM_TYPE, current, AudioManager.FLAG_SHOW_UI)
            Log.d(TAG, "setStreamVolume(当前值, FLAG_SHOW_UI) 成功")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "方案 2 失败", e)
        }

        // 方案 3（兜底）：先调一级音量再恢复，会短暂改变音量
        try {
            val direction = if (current < max) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            am.adjustStreamVolume(STREAM_TYPE, direction, AudioManager.FLAG_SHOW_UI)
            am.setStreamVolume(STREAM_TYPE, current, 0)
            Log.d(TAG, "方案 3（调一级后恢复）成功")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "方案 3 失败", e)
        }

        return false
    }
}
