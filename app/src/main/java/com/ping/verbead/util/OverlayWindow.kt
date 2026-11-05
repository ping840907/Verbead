package com.ping.verbead.util

import android.os.Build
import android.view.WindowManager

/**
 * 讓懸浮視窗的座標以「完整螢幕」為準，包含畫面缺口（瀏海、挖孔）那一側。
 *
 * 沒設定時，橫屏且缺口在左右短邊的手機會把視窗框往內縮一個缺口寬度，
 * 結果貼左的球離螢幕邊緣空了一截，而懸浮球與預覽、選單、抽屜等視窗又都是用同一組座標推算，
 * 位置就全部對不齊。所有懸浮視窗都要套用，才會共用同一個座標原點。
 */
fun WindowManager.LayoutParams.allowDisplayCutout(): WindowManager.LayoutParams {
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    return this
}
