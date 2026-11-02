package com.oaurumo.voldia

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * 快捷设置磁贴：点击即可呼出系统音量面板。
 * 磁贴仅在 Android 7.0（API 24）及以上系统存在。
 */
@RequiresApi(Build.VERSION_CODES.N)
class VolumeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        VolumeController.showSystemPanel(this)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(R.string.volume_percent, VolumeController.percent(this))
        }
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
