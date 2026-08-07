package app.subguard.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.getSystemService
import app.subguard.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 公式の解約ページを開く。
 *
 * ## アプリ内 WebView を使わない理由
 *
 *  - **ログイン済みセッションを引き継げる。** Custom Tabs は Chrome の Cookie を共有するので、
 *    すでにログインしていればそのまま解約画面に入れる。WebView では再ログインになる。
 *  - **フィッシングと区別がつかない。** 解約ページはパスワード入力を伴うことがある。
 *    自前の WebView でパスワード入力画面を出すのは、やってはいけない部類の実装。
 *
 * URL バーは意図的に隠さない。ユーザーが本物のサイトであることを確認できるようにする。
 */
@Singleton
class CancelPageLauncher @Inject constructor() {

    /**
     * 3 段構えで開く。**「開けませんでした」で終わらせない。**
     * 最低でも URL をクリップボードに入れて、ユーザーが自力で辿れるようにする。
     */
    fun open(context: Context, url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return

        // 1. Custom Tabs
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .build()
                .apply { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                .launchUrl(context, uri)
            return
        } catch (_: ActivityNotFoundException) {
            // Chrome 非搭載端末（中華圏に多い）。次へ。
        }

        // 2. 通常のブラウザ Intent
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        } catch (_: ActivityNotFoundException) {
            // ブラウザが 1 つもない。次へ。
        }

        // 3. クリップボードへ
        context.getSystemService<ClipboardManager>()
            ?.setPrimaryClip(ClipData.newPlainText("cancel_url", url))
        Toast.makeText(context, R.string.cancel_url_copied, Toast.LENGTH_LONG).show()
    }
}
