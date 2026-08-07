# 多言語対応と ASO

日本語・英語・簡体字中国語の3言語を、**初期リリースから完全サポート**する。
後から言語を足すのは、文字列リソースを後から整理するのと同義でコストが高い。

## 1. リソース構成

```
app/src/main/res/
├── values/          # en — デフォルト（フォールバック先）
├── values-ja/       # 日本語
└── values-zh-rCN/   # 簡体字中国語
```

**デフォルト (`values/`) を英語にする。** 未対応ロケールの端末で
日本語が出るより英語が出るほうが、世界的には妥当なフォールバックになる。

### 将来の繁体字対応

`values-zh-rTW/` を後から足せるように、文字列IDは
**言語に依存しない命名**にする（`label_cancel` であって `label_jiechu` ではない）。

繁体字は簡体字からの機械変換では不十分（語彙が違う: 「视频」/「影片」、
「网络」/「網路」など）。対応するなら別途翻訳する前提で、初期リリースには含めない。

## 2. 文字列リソースの規律

### 通貨記号を翻訳文に埋め込まない

```xml
<!-- 悪い例 -->
<string name="renewal_amount">¥%1$s が請求されます</string>

<!-- 良い例 -->
<string name="renewal_amount">%1$s が請求されます</string>
```

`%1$s` には `Money.format(locale)` の結果（`¥1,590` / `$15.49` / `¥68.00`）が入る。
記号を文字列リソースに書くと、日本語UIで米国のサブスクを表示したときに `¥15.49` になる。

これは3言語 × 3通貨の組み合わせで必ず起きるので、**規律として徹底する**。

### 日付も同様

```kotlin
DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withLocale(Locale.getDefault())
    .withZone(ZoneId.systemDefault())
    .format(renewalAt)
```

`"yyyy年M月d日"` のようなパターンを文字列リソースに置かない。
`ofLocalizedDateTime` がロケールごとに適切な形式を出す。

### 複数形

英語の `1 subscription` / `2 subscriptions` は `<plurals>` を使う。
日本語と中国語には複数形がないが、`<plurals>` で `other` のみ定義すれば問題ない。

```xml
<plurals name="subscription_count">
    <item quantity="one">%d subscription</item>
    <item quantity="other">%d subscriptions</item>
</plurals>
```

### 文字列の長さ

ドイツ語ほどではないが、**英語は日本語の 1.5〜2 倍の長さになる**。

| ja | en |
|---|---|
| 解約ページを開く | Open cancellation page |
| 通知アクセスを許可する | Allow notification access |

ボタンのレイアウトは固定幅にせず、折り返しを許容する。
Compose では `maxLines` を安易に 1 にしない。

## 3. 通知チャネル名

チャネルの名前と説明は**作成時のロケールで固定される**。
端末の言語を変えても自動では追随しない。

対処: アプリ起動時に、現在のロケールでチャネルを作り直す
（`createNotificationChannel` は同じIDなら名前を更新する。ただし
importance など一部の属性はユーザー設定が優先され更新されない）。

## 4. 中華圏向けの技術的配慮

### GMS 依存の隔離

中国本土の端末には Google Play サービスが入っていないことが多い。
初期リリースの配布は Google Play のみなので直接の問題にはならないが、
**将来の他ストア配布を塞がないように**、GMS 依存を interface の裏に置く。

```kotlin
// domain 層は Play Billing を知らない
interface EntitlementRepository {
    suspend fun isUnlocked(): Boolean
    fun observeUnlocked(): Flow<Boolean>
}

// data 層の実装が Play Billing に依存する
class PlayBillingEntitlementRepository(...) : EntitlementRepository
```

こうしておけば、別ストア版で `HuaweiIapEntitlementRepository` などに
差し替えられる。実装コストはほぼゼロで、選択肢を残せる。

### Custom Tabs のフォールバック

Chrome が入っていない端末では Custom Tabs が使えない。
必ず `ACTION_VIEW` → クリップボードの3段フォールバックを実装する
（→ [reminder-and-navigation.md](reminder-and-navigation.md)）。

### アプリ内解約のみのサービスが多い

中華圏のサービスは Web に解約導線がなく、`cancel_url` が `null` になるものが多い。
**この地域では `manual_steps` の質がアプリの実用性を直接決める**
（→ [cancel-url-database.md](cancel-url-database.md)）。

さらに、微信支付・支付宝経由で契約した場合は
**サービス側の解約と決済側の免密支付解除の両方が必要**。
片方だけでは引き落としが止まらないことがあるため、UI で両方を案内する。

## 5. ASO（ストア最適化）

### キーワード

各言語で、ユーザーが実際に打つ言葉を優先する。

| 言語 | 主要キーワード |
|---|---|
| ja | サブスク 解約、解約 忘れ、無料体験 解約、サブスク 管理、無料トライアル リマインダー、解約 リマインダー |
| en | subscription cancel reminder, free trial reminder, cancel subscription, subscription tracker, trial expiry alert, avoid subscription charges |
| zh | 订阅 取消 提醒, 自动扣费 解除, 免费试用 提醒, 订阅管理, 取消自动续费 |

**「自動扣費解除」は中華圏で最も検索意図が強い。**
「订阅管理」のような一般語より、「解除」「取消」を含む語のほうが
このアプリの機能と一致する。

### アプリ名

ストアのアプリ名にキーワードを含めるのが定石。

| 言語 | 案 |
|---|---|
| ja | SubGuard - サブスク解約リマインダー |
| en | SubGuard - Free Trial Reminder |
| zh | SubGuard - 订阅到期提醒 |

**「解約代行」を示唆する語を入れない**（→ [policy-compliance.md](policy-compliance.md)）。
「自動解約」「ワンタップ解約」はキーワードとしては強いが、使わない。

### 短い説明（80文字）

| 言語 | 案 |
|---|---|
| ja | 無料体験の解約忘れを防ぐ。終了日を自動検知し、解約ページへワンタップでご案内。 |
| en | Never forget to cancel a free trial. Auto-detects trial end dates and takes you to the official cancellation page. |
| zh | 不再忘记取消免费试用。自动检测到期日，一键前往官方取消页面。 |

### スクリーンショット

各言語で撮り直す。UI の言語が英語のまま日本のストアに出ていると、
それだけでインストール率が落ちる。

撮るべき画面の優先順（→ UI モックアップ）:
1. **ホーム（一覧）** — カウントダウンが並ぶ画面。何のアプリか一目で分かる
2. **ロック画面通知** — 「3時間前に教えてくれる」という価値の証明
3. **詳細画面** — 解約ページへの導線
4. **検知確認** — 自動検知の訴求
5. **オンボーディング（プライバシー）** — 通知アクセスへの不安を先回りで解消

**2番目にロック画面通知を置く。** ユーザーが本当に欲しいのは
「一覧を管理すること」ではなく「忘れそうなときに教えてもらうこと」なので、
その価値を最初のほうで見せる。

## 6. 翻訳の運用

- **機械翻訳をそのまま出さない。** 特に権限説明とプライバシー関連の文言は、
  不自然な訳がそのまま不信感になる。この部分だけは人間のレビューを通す。
- **`manual_steps`（解約手順）は各国のアプリの実際のメニュー名と一致させる。**
  「设置」を「設定」と訳すのではなく、中国語版アプリに実際に表示される文字列を使う。
  ここがずれると手順として役に立たない。
- 用語集を作る: `subscription` = サブスク / 订阅、`cancel` = 解約 / 取消、
  `free trial` = 無料体験 / 免费试用。ドキュメント内で表記を揺らさない。
