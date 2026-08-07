# リマインダーと解約導線

「通知を出す」と「解約ページへ運ぶ」の2つ。ここが実際にユーザーの課金を止める部分であり、
**取りこぼしが直接お金の損失になる**唯一の機能。

## 1. スケジューリング: WorkManager

### AlarmManager の exact alarm を使わない理由

当初案は `AlarmManager` だったが、採用しない。

- **Android 12 (API 31) 以降、`SCHEDULE_EXACT_ALARM` は Play の制限対象**になった。
  アラーム・タイマーアプリなど「正確な時刻が本質的な機能」でないと承認されにくく、
  リマインダーアプリでの利用は審査で問題になり得る。
- **そもそも精度が要らない。** 24時間前・3時間前の通知に、秒単位の正確さは不要。
  数分〜十数分の遅延は許容できる。
- **WorkManager は端末再起動をまたいで復元される。** `AlarmManager` は
  `BOOT_COMPLETED` を自前で受けて再登録する必要があり、その分だけ壊れる箇所が増える。

精度が要らないのに制限の強い API を選ぶ理由がない。

### Work の登録

サブスク1件につき、24時間前と3時間前の2本を登録する。

```kotlin
private fun enqueue(sub: Subscription, offsetHours: Long) {
    val fireAt = sub.renewalAt.minus(offsetHours, ChronoUnit.HOURS)
    val delay = Duration.between(Instant.now(), fireAt)
    if (delay.isNegative) return   // すでに過ぎている枠は登録しない

    val req = OneTimeWorkRequestBuilder<ReminderWorker>()
        .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
        .setInputData(workDataOf(
            KEY_SUBSCRIPTION_ID to sub.id,
            KEY_OFFSET_HOURS to offsetHours,
        ))
        .build()

    workManager.enqueueUniqueWork(
        uniqueWorkName(sub.id, offsetHours),   // "reminder-{id}-{offset}"
        ExistingWorkPolicy.REPLACE,            // 更新日が変わったら差し替わる
        req,
    )
}
```

**`ExistingWorkPolicy.REPLACE` が重要。** ユーザーが更新日を編集したとき、
古い Work が残っていると誤ったタイミングで通知が飛ぶ。同じ一意名で上書きすることで、
「1サブスク × 1オフセット = 常に1本」を保証する。

**制約 (`Constraints`) は付けない。** ネットワークもバッテリーも不要な処理なので、
`setRequiredNetworkType` などを付けると発火が不必要に遅れる。

### 日次リコンサイル

Work の取りこぼしは必ず起きる（OS のプロセス強制終了、Work の期限切れ、
アプリのアップデートによるキャンセルなど）。保険として日次で全件を再武装する。

```kotlin
PeriodicWorkRequestBuilder<ReconcileRemindersWorker>(1, TimeUnit.DAYS)
    .build()
    .also {
        workManager.enqueueUniquePeriodicWork(
            "reconcile-reminders",
            ExistingPeriodicWorkPolicy.KEEP,
            it,
        )
    }
```

このワーカーがやること:
1. `is_active = 1` の全サブスクを取得
2. 各件について `enqueue()` を呼び直す（`REPLACE` なので冪等）
3. 更新日を過ぎたサブスクの `reminder_log` を整理

**アプリ起動時にも同じ処理を走らせる。** 起動は最も確実に Work を再武装できる機会。

## 2. 通知チャネル

緊急度ごとにチャネルを分ける。ユーザーが「24時間前は要らないが3時間前は欲しい」と
設定できるようにするため。1つのチャネルにまとめると、切ると全部切れてしまう。

| チャネルID | 重要度 | 用途 |
|---|---|---|
| `reminder_urgent` | `IMPORTANCE_HIGH` | 3時間前。ヘッドアップ通知＋音 |
| `reminder_normal` | `IMPORTANCE_DEFAULT` | 24時間前 |
| `detection` | `IMPORTANCE_DEFAULT` | 新しいサブスクを検知したとき |
| `service_status` | `IMPORTANCE_LOW` | 通知アクセスが無効になった等の警告 |

チャネル名と説明文は**すべて3言語で用意する**。チャネルは一度作ると名前を変えても
反映されない仕様なので、初回作成時の文言を慎重に決める。

### Android 13+ の `POST_NOTIFICATIONS`

API 33 以降、通知の表示自体にランタイム権限が必要。
オンボーディングの早い段階（通知アクセスより前）で取る。

**拒否された場合でもアプリを動かす。** 一覧画面でカウントダウンは見られるので、
「通知は出ないが記録はできる」状態として成立させ、ホーム画面上部に
「通知を有効にする」バナーを常設する。

## 3. 通知の中身

```kotlin
NotificationCompat.Builder(ctx, channelId)
    .setSmallIcon(R.drawable.ic_shield)
    .setContentTitle(title)      // "あと3時間で Netflix の課金が始まります"
    .setContentText(body)        // "無料体験は今日 12:28 に終了し、¥1,590 が請求されます。"
    .setContentIntent(openDetailPendingIntent)
    .addAction(0, cancelLabel, openCancelUrlPendingIntent)   // "解約ページへ"
    .addAction(0, keepLabel, dismissPendingIntent)           // "継続する"
    .setAutoCancel(true)
    .build()
```

### 「解約ページへ」はアプリを経由しない

通知アクションから **直接 Custom Tabs / ブラウザを開く**。
アプリのホーム画面を挟むと、そこで気が変わったり別の操作に流れたりする。
ユーザーがやりたいのは解約であって、アプリを見ることではない。

### ロック画面での情報の出し方

ここは**トレードオフがあり、既定値は要検討**。

| 方針 | 利点 | 欠点 |
|---|---|---|
| 内容を全部出す | 到達力が最大。ロック解除せず判断できる | 他人に画面を見られると加入サービスが漏れる |
| 内容を隠す | プライバシーが守られる | ロック解除の一手間で行動率が落ちる |

実装は `setVisibility(VISIBILITY_PRIVATE)` と `setPublicVersion()` の組み合わせで
「ロック画面では『解約期限が近づいています』とだけ出す」ことができる。
**設定項目として用意し、既定値はユーザーテストで決める。**

## 4. 解約ページへの遷移

### Chrome Custom Tabs

```kotlin
CustomTabsIntent.Builder()
    .setShowTitle(true)
    .setUrlBarHidingEnabled(false)   // URL を隠さない = フィッシングでないことを示す
    .build()
    .launchUrl(context, Uri.parse(cancelUrl))
```

アプリ内 WebView を使わない理由:

- **ログイン済みセッションを引き継げる。** Custom Tabs は Chrome の Cookie を共有するので、
  すでに Netflix にログインしていればそのまま解約画面に入れる。WebView では再ログインになる。
- **信頼性の表示。** URL バーが見えるので、ユーザーは本物の Netflix であることを確認できる。
  解約ページはパスワード入力を伴うことがあり、WebView で出すのは
  フィッシングと区別がつかず、やってはいけない。

**`setUrlBarHidingEnabled(false)` は意図的。** URL を常に見せる。

### フォールバック

Custom Tabs 非対応の端末は珍しくない（特に中華圏の Chrome 非搭載端末）。

```kotlin
fun openCancelPage(context: Context, url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder()./* ... */.build().launchUrl(context, uri)
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e2: ActivityNotFoundException) {
            // ブラウザが1つもない端末。URL をコピーして案内する
            copyToClipboard(context, url)
            showMessage(context, R.string.cancel_url_copied)
        }
    }
}
```

3段構えにする。**「解約ページを開けませんでした」で終わらせない** ——
最低でも URL をクリップボードに入れて、ユーザーが自力で辿れるようにする。

### Google Play のサブスク管理画面

Play で課金しているサブスクは、専用の deep link がある。

```
https://play.google.com/store/account/subscriptions?sku=<productId>&package=<packageName>
```

`sku` と `package` が分かる場合はこちらを優先する（該当サブスクの管理画面に直接飛ぶ）。
分からない場合は引数なしの `https://play.google.com/store/account/subscriptions` で
一覧まで運ぶ。

**iOS / Apple のサブスク管理画面は Android からは開けない。**
カタログに Apple 系サービスを入れる場合、`cancel_url` は Web 版の解約導線を指す。

## 5. 「解約できたか」の確認

アプリは解約の成否を知る手段を持たない（解約は外部サイトで行われる）。
そこで **Custom Tabs から戻ってきたときに聞く**。

> 「Netflix を解約できましたか?」 → [解約した] / [まだ]

「解約した」なら `is_active = 0` にしてリマインダーを解除する。
「まだ」なら何もせず、リマインダーは生きたままにする。

**推測で `is_active` を変えない。** ユーザーが解約ページを開いただけで
「解約済み」と判定すると、リマインダーが止まり、そのまま課金される。
このアプリで最悪の失敗モードなので、必ず明示的な確認を取る。
