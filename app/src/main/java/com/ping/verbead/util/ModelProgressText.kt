package com.ping.verbead.util

import android.content.Context
import com.ping.verbead.R
import com.ping.verbead.engine.ModelDownloader

/** 把資料層回報的下載進度階段轉成目前語言的文字（資料層不持有介面文字）。 */
fun ModelDownloader.Progress.label(context: Context): String = when (stage) {
    ModelDownloader.Stage.DOWNLOADING -> context.getString(R.string.model_progress_downloading)
    ModelDownloader.Stage.DOWNLOADING_FILE -> context.getString(R.string.model_progress_downloading_files, current, total)
    ModelDownloader.Stage.EXTRACTING -> context.getString(R.string.model_progress_extracting, detail)
}
