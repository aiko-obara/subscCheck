# 端末メーカー別の省電力対策

**このアプリの最大の技術的リスクは、通知が届かないこと。**

リマインダーが1回飛ばなかっただけで、ユーザーは課金され、アプリは目的を果たせない。
そして Android では、**メーカー独自の省電力機能がアプリのバックグラウンド処理を無言で殺す**。

Xiaomi、Huawei、OPPO、vivo などの端末では、標準の WorkManager でも
アプリがスワイプで終了されたり数日使われなかったりすると、
スケジュール済みの処理ごと停止させられることがある。

参考: [dontkillmyapp.com](https://dontkillmyapp.com/) — 各メーカーの挙動をまとめたコミュニティ資料。

## 1. 方針

**OS の挙動は変えられないので、ユーザーに設定してもらうしかない。**
できるのは「設定してもらう確率を上げること」だけ。

そのために:

1. 該当メーカーの端末でだけ案内を出す（全端末に出すとノイズになる）
2. 具体的な手順を、そのメーカーの実際のメニュー名で示す
3. 設定画面へ直接飛ばす。飛べなければ手順テキストにフォールバックする
4. **必要以上に不安を煽らない**

## 2. 対象メーカーの判定

```kotlin
enum class OemVendor { XIAOMI, HUAWEI, OPPO, VIVO, SAMSUNG, ONEPLUS, MEIZU, ASUS, OTHER }

fun detectVendor(): OemVendor = when (Build.MANUFACTURER.lowercase()) {
    "xiaomi", "redmi", "poco"   -> OemVendor.XIAOMI
    "huawei", "honor"           -> OemVendor.HUAWEI
    "oppo", "realme"            -> OemVendor.OPPO
    "vivo", "iqoo"              -> OemVendor.VIVO
    "oneplus"                   -> OemVendor.ONEPLUS
    "samsung"                   -> OemVendor.SAMSUNG
    "meizu"                     -> OemVendor.MEIZU
    "asus"                      -> OemVendor.ASUS
    else                        -> OemVendor.OTHER
}
```

`OTHER` の場合は案内を出さない。Pixel などの素の Android では
標準のバッテリー最適化設定だけで足りる。

## 3. 設定画面への遷移

### 必ず try/catch する

各メーカーの設定画面を開く Intent は **非公開 API であり、いつ消えてもおかしくない**。
存在しない Activity を起動しようとすれば `ActivityNotFoundException` で落ちる。
機種やROMバージョンによっては `SecurityException` も飛ぶ。

```kotlin
fun openVendorSettings(context: Context, vendor: OemVendor): Boolean {
    for (intent in candidateIntents(vendor)) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        } catch (e: ActivityNotFoundException) {
            // 次の候補へ
        } catch (e: SecurityException) {
            // 一部ROMで権限エラーになる。次の候補へ
        }
    }
    // すべて失敗 → 標準のアプリ情報画面にフォールバック
    return try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        true
    } catch (e: Exception) {
        false   // UI 側は手順テキストのみ表示する
    }
}
```

**戻り値で成否を返し、UI が手順テキストの見せ方を変えられるようにする。**

### 候補 Intent は複数持つ

同じメーカーでも ROM のバージョンで Activity 名が変わる。
メーカーごとに複数の候補を順に試す。

**候補の Activity 名はこのドキュメントに列挙しない。**
バージョン依存で必ず陳腐化するため、実機で確認した値を
コード内の1箇所（`VendorSettingsIntents.kt`）にまとめ、
コメントで「どの機種のどのROMで確認したか」を残す。

## 4. 手順テキスト

Intent が失敗しても、**手順さえ分かればユーザーは自力で辿れる**。
これが最後の砦なので、必ず全メーカー分を3言語で用意する。

### Xiaomi (MIUI / HyperOS)

| ja | 手順 |
|---|---|
| 1 | 「設定」→「アプリ」→「アプリを管理」を開く |
| 2 | 一覧から **SubGuard** を選ぶ |
| 3 | 「自動起動」を**オン**にする |
| 4 | 「省電力」→「制限なし」を選ぶ |

Xiaomi は「自動起動 (Autostart)」が既定でオフになっており、
これをオンにしないとプロセスが復帰できない。**最も重要な1項目**。

### Huawei (EMUI / HarmonyOS)

| ja | 手順 |
|---|---|
| 1 | 「設定」→「バッテリー」→「アプリ起動」を開く |
| 2 | **SubGuard** の自動管理をオフにする |
| 3 | 「自動起動」「他のアプリからの起動」「バックグラウンドで実行」をすべてオンにする |

### OPPO / realme (ColorOS)

| ja | 手順 |
|---|---|
| 1 | 「設定」→「バッテリー」→「アプリのバッテリー管理」 |
| 2 | **SubGuard** を選び「バックグラウンド実行を許可」をオンにする |
| 3 | 「設定」→「アプリ管理」→「自動起動」でも SubGuard をオンにする |

### vivo (Funtouch OS / OriginOS)

| ja | 手順 |
|---|---|
| 1 | 「設定」→「バッテリー」→「バックグラウンド消費電力管理」 |
| 2 | **SubGuard** の「バックグラウンド実行を許可」をオンにする |

### Samsung (One UI)

| ja | 手順 |
|---|---|
| 1 | 「設定」→「バッテリー」→「バックグラウンド使用制限」 |
| 2 | **SubGuard** が「スリープ中のアプリ」に入っていないことを確認する |

Samsung は他社より穏当だが、「使用していないアプリをスリープ状態にする」が
既定でオンのため、しばらく開かないと止まる。

> 上記のメニュー名は ROM のバージョンで変わる。
> **実機で確認した文言に合わせて更新すること。** リリース前に主要機種で確認する
> （→ [roadmap.md](roadmap.md) Phase 4）。

## 5. いつ案内するか

| タイミング | 内容 |
|---|---|
| オンボーディング 4/4 | 該当メーカーの端末でのみ表示。スキップ可能 |
| 設定画面 | 常設。「通知が届かない場合」としていつでもアクセスできる |
| 通知の取りこぼしを検知したとき | 下記参照 |

### 取りこぼしの検知

日次リコンサイル Worker が動いた形跡が数日ないなら、
省電力機能で止められている可能性が高い。

```
最終実行時刻を DataStore に記録
  ↓
アプリ起動時に確認
  ↓
48時間以上動いていない かつ 該当メーカーの端末
  ↓
ホーム画面上部にバナー: 「通知が届かない可能性があります」→ 案内画面へ
```

**押しつけがましくしない。** バナーは閉じられるようにし、
一度閉じたら次に条件を満たすまで再表示しない。

## 6. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` を使わない

Android 標準のバッテリー最適化除外をアプリから直接要求する権限があるが、
**初期リリースでは使わない**。

- **Play の制限対象**であり、承認される用途は限定的。
  「リマインダーアプリだから」で通る保証がなく、審査で弾かれるリスクがある。
- **メーカー独自の省電力機能には効かない。** Xiaomi の「自動起動」は
  この権限とは別系統であり、除外しても止められる。
  つまり**審査リスクを負う割に、肝心の問題が解決しない**。

まずは設定画面への誘導だけで実装し、実運用で取りこぼしが問題になったら再検討する。
その判断材料として、5章の「取りこぼしの検知」を先に入れておく。

## 7. 割り切り

**すべての端末で 100% 通知を届けることはできない。** これは Android の現実であり、
アプリ側の実装で完全には解決できない。

そのうえで取れる手は:

- WorkManager を使う（メーカー対策済みの標準API）
- 日次リコンサイルで再武装する
- アプリ起動のたびに再武装する
- 取りこぼしを検知してユーザーに知らせる
- 設定を促す

これらを積み重ねて到達率を上げていく。
**「絶対に通知します」と約束しない** —— ストア説明にもアプリ内にも書かない。
守れない約束は低評価レビューになって返ってくる。
