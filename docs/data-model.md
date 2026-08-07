# データモデル

すべて端末内で完結する。同期先のサーバーは存在しない（解約URL DB の**取得**だけが唯一の外部通信で、
これは読み取り専用・匿名）。

## 1. テーブル定義

### `subscriptions`

ユーザーが管理しているサブスクの本体。

| カラム | 型 | 制約 | 説明 |
|---|---|---|---|
| `id` | INTEGER | PK, autoincrement | |
| `service_id` | TEXT | nullable | `cancel_urls.json` のサービスID。手動入力の独自サービスは null |
| `service_name` | TEXT | not null | 表示名。カタログ由来でもユーザーが上書き可能 |
| `cancel_url` | TEXT | nullable | 解約ページ直行URL。不明なら null（ボタンを出さない） |
| `renewal_date` | INTEGER | not null, indexed | 次回更新／トライアル終了日時。**UTC epoch millis** |
| `amount_minor` | INTEGER | not null | 金額（マイナー単位の整数） |
| `currency` | TEXT | not null | ISO 4217（`JPY` / `USD` / `CNY`） |
| `is_trial` | INTEGER | not null | 無料体験中か |
| `trial_end_date` | INTEGER | nullable | 体験終了日時。`is_trial` が真のとき `renewal_date` と一致するのが基本 |
| `is_active` | INTEGER | not null, default 1 | 解約済み・非表示にしたものは 0 |
| `source` | TEXT | not null | `AUTO` / `MANUAL` |
| `note` | TEXT | nullable | ユーザーの自由記述 |
| `icon_ref` | TEXT | nullable | カタログのアイコン参照。null なら頭文字フォールバック |
| `created_at` | INTEGER | not null | |
| `updated_at` | INTEGER | not null | |

**`renewal_date` にインデックスを張る。** 一覧クエリが常にこの列でソートするため。

#### 金額を `Float` にしない理由

当初案は `amount: Float` だったが、これは3つの理由で破綻する。

1. **通貨ごとに小数桁が違う。** ¥1,590（小数0桁）と $15.49（小数2桁）と ¥68 CNY（小数2桁）を
   同じ `Float` に入れると、表示のたびに桁数を推測することになる。
2. **浮動小数の誤差。** 金額の加算（月額合計の表示）で 1 円ずれる。金融系で `Float` を使わないのは常識。
3. **通貨コードが失われる。** `1590` だけでは ¥1,590 なのか $1,590 なのか判別できない。

そこで **マイナー単位の整数 + ISO 4217 通貨コード**に分割する。
小数桁は `java.util.Currency.getInstance(code).defaultFractionDigits` から得る。

```kotlin
// domain/model/Money.kt — Android 非依存
data class Money(val minor: Long, val currency: String) {
    fun format(locale: Locale): String {
        val cur = Currency.getInstance(currency)
        val fmt = NumberFormat.getCurrencyInstance(locale).apply { this.currency = cur }
        return fmt.format(minor.toBigDecimal().movePointLeft(cur.defaultFractionDigits))
    }
}
```

`¥1,590` は `Money(1590, "JPY")`、`$15.49` は `Money(1549, "USD")` になる。

---

### `detection_events`

通知から検知した候補。**ここに入っただけでは、ユーザーには「サブスク」として見えない。**

| カラム | 型 | 制約 | 説明 |
|---|---|---|---|
| `id` | INTEGER | PK, autoincrement | |
| `package_name` | TEXT | not null | 通知元アプリのパッケージ名 |
| `matched_pattern_id` | TEXT | not null | `cancel_urls.json` の `detection_patterns[].id` |
| `detected_at` | INTEGER | not null | |
| `status` | TEXT | not null | `PENDING` / `CONFIRMED` / `DISMISSED` |
| `subscription_id` | INTEGER | nullable, FK | 承認時に作られた `subscriptions.id` |
| `x_service_name` | TEXT | nullable | 抽出したサービス名 |
| `x_amount_minor` | INTEGER | nullable | 抽出した金額 |
| `x_currency` | TEXT | nullable | 抽出した通貨 |
| `x_renewal_date` | INTEGER | nullable | 抽出した更新日時 |
| `confidence` | REAL | not null | 0.0〜1.0 |

#### 通知の本文は保存しない

`x_*` は**抽出済みの構造化フィールドだけ**。元の通知テキストは、抽出が終わった時点でメモリから捨てる。

これは2つの意味を持つ。

- **プライバシー**: 決済通知の本文には、口座末尾やカード下4桁が含まれることがある。保存しなければ漏れない。
- **ポリシー適合**: Google Play の通知アクセスポリシーは、通知内容の目的外利用と外部送信を禁じている。
  保存しない設計は、データ安全性フォームでの説明を最も簡潔にする（→ [policy-compliance.md](policy-compliance.md)）。

デバッグ時に本文を見たくなるが、**デバッグビルドでもログに出さない**こと。
`Log.d` に流した瞬間に logcat 経由で他アプリから読める可能性が生まれる。

---

### `reminder_log`

発火済みリマインダーの記録。二重通知の防止と、ユーザーからの「通知が来なかった」報告の調査に使う。

| カラム | 型 | 制約 | 説明 |
|---|---|---|---|
| `id` | INTEGER | PK, autoincrement | |
| `subscription_id` | INTEGER | not null, FK, ON DELETE CASCADE | |
| `offset_hours` | INTEGER | not null | `24` / `3` |
| `fired_at` | INTEGER | not null | |
| `work_request_id` | TEXT | nullable | WorkManager の UUID |

`(subscription_id, offset_hours, renewal_date)` の組で一意にしたいが、`renewal_date` は
`subscriptions` 側にあるため、更新日が変わったら該当ログを削除する運用にする
（`ScheduleRemindersUseCase` が再スケジュール時に行う）。

---

### `service_catalog`

`cancel_urls.json` から同期したカタログのローカルキャッシュ。

| カラム | 型 | 制約 | 説明 |
|---|---|---|---|
| `service_id` | TEXT | PK | |
| `name_en` / `name_ja` / `name_zh` | TEXT | not null | |
| `regions` | TEXT | not null | カンマ区切り（`JP,US,GLOBAL`） |
| `cancel_url` | TEXT | nullable | |
| `cancel_url_overrides` | TEXT | nullable | ロケール別URLの JSON 文字列 |
| `keywords` | TEXT | not null | 検知・オートコンプリート用。カンマ区切り |
| `icon` | TEXT | nullable | |

正規化してテーブルを分けたくなるが、**カタログは丸ごと置き換えるだけの読み取り専用データ**なので、
配列は文字列に押し込んでおくほうが同期処理が単純になる。件数も高々数百。

---

## 2. 暗号化

### 方式

**SQLCipher（`net.zetetic:sqlcipher-android`）で DB ファイル全体を暗号化する。**

```kotlin
val factory = SupportOpenHelperFactory(passphrase.toByteArray())
Room.databaseBuilder(context, AppDatabase::class.java, "subguard.db")
    .openHelperFactory(factory)
    .build()
```

### パスフレーズの管理

1. 初回起動時に `SecureRandom` で 32 バイトを生成。
2. `androidx.security:security-crypto` の `EncryptedSharedPreferences` に保存。
   この暗号鍵自体は Android Keystore に格納され、**アプリのプロセス外からは取り出せない**。
3. 以後の起動では EncryptedSharedPreferences から読み出す。

### 必ず設定すること

```xml
<application
    android:allowBackup="false"
    android:dataExtractionRules="@xml/data_extraction_rules"
    ...>
```

`allowBackup="true"`（既定値）のままだと、暗号化された DB ファイルが Google ドライブの
自動バックアップに乗る。一方でパスフレーズは Keystore にあり端末を跨げないため、
**復元しても開けない DB がユーザーの手元に残る**という最悪の結果になる。
バックアップは明示的に無効化する。

### 鍵ローテーション

初期リリースでは実装しない。必要になった場合は SQLCipher の `PRAGMA rekey` を使うが、
移行中の電源断でDBが壊れるリスクがあるため、実装するなら一時ファイルへの再書き出し方式にする。

---

## 3. マイグレーション

```kotlin
@Database(
    entities = [...],
    version = 1,
    exportSchema = true
)
```

- **`exportSchema = true` を必ず有効にし、`app/schemas/` をリポジトリにコミットする。**
  これがないと、後から自動マイグレーションもマイグレーションテストも書けない。
- `fallbackToDestructiveMigration()` は**使わない**。ユーザーのサブスクデータが消えることは、
  このアプリでは「課金を防げなかった」と同義の致命的な障害である。
- スキーマを変えるたびに `MigrationTestHelper` を使ったマイグレーションテストを追加する。

### 想定される最初のマイグレーション

Phase 4 以降で必要になりそうなもの:

- サービスごとの通知タイミング設定（現在は 24h / 3h 固定）
- 解約完了の記録（`cancelled_at`）— 「守れた金額」の累計表示に使える
- 家族・複数アカウントの区別
