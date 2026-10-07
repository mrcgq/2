package com.xlink.android.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

object ClipboardUtil {

    fun copy(context: Context, text: String, label: String = "xlink config") {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun paste(context: Context): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!cm.hasPrimaryClip()) return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0)?.coerceToText(context)?.toString()
    }
}
