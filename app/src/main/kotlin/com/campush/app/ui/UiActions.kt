package com.campush.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

/** 复制播放地址。 */
fun Context.copyPlayUrlToClipboard(text: String) {
    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("CamPush 播放地址", text))
}

/** 打开系统分享面板，分享唯一播放地址。 */
fun Context.sharePlayUrl(text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "CamPush 局域网直播")
        putExtra(Intent.EXTRA_TEXT, "CamPush 局域网直播地址（仅同一局域网可打开）：$text")
    }
    runCatching { startActivity(Intent.createChooser(send, "分享播放地址")) }
        .onFailure { Toast.makeText(this, "没有可用的分享应用", Toast.LENGTH_SHORT).show() }
}

/** 1001/1007 等权限类错误：跳到本应用系统设置。 */
fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
        .onFailure { Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show() }
}

/** 复制 AP 隔离排查清单。 */
fun Context.copyChecklistToClipboard(text: String) {
    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("AP 隔离排查清单", text))
    Toast.makeText(this, "排查清单已复制", Toast.LENGTH_SHORT).show()
}
