package com.oaurumo.voldia

import android.content.Context

/**
 * 悬浮球与音量卡片外观设置，通过 SharedPreferences 持久化。
 *
 * - [ballSizeDp]：悬浮球直径，单位 dp，范围 [MIN_SIZE_DP, MAX_SIZE_DP]，默认 56dp。
 * - [ballOpacity]：悬浮球不透明度，范围 [0, 100]，100 表示完全不透明。
 * - [cardWidthPercent]：音量卡片宽度占屏幕宽度的百分比，范围 [MIN_CARD_WIDTH_PERCENT, MAX_CARD_WIDTH_PERCENT]，默认 80%。
 */
object BallPrefs {

    private const val PREFS_NAME = "voldia_ball"
    private const val KEY_SIZE = "ball_size_dp"
    private const val KEY_OPACITY = "ball_opacity"
    private const val KEY_CARD_WIDTH = "card_width_percent"

    const val MIN_SIZE_DP = 40
    const val MAX_SIZE_DP = 96
    const val DEFAULT_SIZE_DP = 56

    const val MIN_OPACITY = 1
    const val MAX_OPACITY = 100
    const val DEFAULT_OPACITY = 100

    const val MIN_CARD_WIDTH_PERCENT = 30
    const val MAX_CARD_WIDTH_PERCENT = 100
    const val DEFAULT_CARD_WIDTH_PERCENT = 80

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSizeDp(context: Context): Int =
        prefs(context).getInt(KEY_SIZE, DEFAULT_SIZE_DP).coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)

    fun setSizeDp(context: Context, sizeDp: Int) {
        prefs(context).edit().putInt(KEY_SIZE, sizeDp.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)).apply()
    }

    fun getOpacity(context: Context): Int =
        prefs(context).getInt(KEY_OPACITY, DEFAULT_OPACITY).coerceIn(MIN_OPACITY, MAX_OPACITY)

    fun setOpacity(context: Context, opacity: Int) {
        prefs(context).edit()
            .putInt(KEY_OPACITY, opacity.coerceIn(MIN_OPACITY, MAX_OPACITY)).apply()
    }

    fun getCardWidthPercent(context: Context): Int =
        prefs(context).getInt(KEY_CARD_WIDTH, DEFAULT_CARD_WIDTH_PERCENT)
            .coerceIn(MIN_CARD_WIDTH_PERCENT, MAX_CARD_WIDTH_PERCENT)

    fun setCardWidthPercent(context: Context, percent: Int) {
        prefs(context).edit()
            .putInt(KEY_CARD_WIDTH, percent.coerceIn(MIN_CARD_WIDTH_PERCENT, MAX_CARD_WIDTH_PERCENT))
            .apply()
    }
}
