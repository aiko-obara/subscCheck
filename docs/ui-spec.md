# UI 仕様とテスト方針

**確定仕様（操作できるプロトタイプ）:**
https://claude.ai/code/artifact/aa1acf63-f2dc-4b74-94bb-e18eb468bce5

そこに載っているもの:

- 3 言語 × 4 描画状態を切り替えられるインタラクティブ・プロトタイプ
- `testTag` の命名規約
- **描画状態マトリクス** — 各画面の `UiState` と、そのとき「出るもの／出てはいけないもの」
- **コンポーネント・カタログ** — 各パーツの全状態
- **イベント仕様表** — testTag × 操作 × イベント × 期待する結果
- **描画イベント仕様** — 状態遷移の検証項目

プロトタイプで要素をタップすると、発火したイベント名と `testTag` がログに出る。
**そこに出た名前がそのままテストのセレクタになる。**

---

## 1. testTag は 1 箇所にしか書かない

[`app/src/main/kotlin/app/subguard/ui/TestTags.kt`](../app/src/main/kotlin/app/subguard/ui/TestTags.kt)
に集約し、画面側とテスト側の両方がそこを参照する。

```kotlin
// 画面
SubscriptionCard(
    modifier = Modifier.testTag(TestTags.Home.card(subscription.id)),
    ...
)

// テスト
composeRule.onNodeWithTag(TestTags.Home.card(1L)).performClick()
```

文字列リテラルを 2 箇所に書くと、片方を直したときにテストが
「存在しないノード」を探して落ちるか、**もっと悪いことに、消えたはずの要素を
探し続けて通ってしまう**。

### 命名規約

```
subguard:<screen>:<element>[:<id>]
```

`element` は**役割**で名づける。`cancelButton` は可、`redButton` は不可。
見た目で名づけると、デザインを変えた瞬間に名前が嘘になる。

---

## 2. テストの三層

| 層 | 何を検証するか | どこで動くか | 速さ |
|---|---|---|---|
| **ロジック** | 検知・金額・日付・緊急度 | JVM（`:core:detection`） | 秒 |
| **パーツ** | 1 コンポーネントの描画とイベント | Robolectric または端末 | 数秒 |
| **画面** | `UiState` ごとの描画、状態遷移 | Robolectric または端末 | 数十秒 |

**ロジック層はすでに 69 テストが通っている**（`:core:detection`）。
最も壊れやすい部分を Android から切り離してあるのは、この速さを得るため。

Compose のテストは `androidx.compose.ui:ui-test-junit4` を使う。
Robolectric で JVM 上を動かせれば CI が速くなるが、Compose の描画テストは
Robolectric で不安定になることがあるため、**まずは端末／エミュレータ前提の
`androidTest` で書き、安定してから JVM 移行を検討する**。

---

## 3. パーツ単位テストの書き方

イベント仕様表の 1 行が 1 テスト。

```kotlin
@Test
fun `card click emits onCardClick with the id`() {
    var clicked: Long? = null
    composeRule.setContent {
        SubscriptionCard(
            state = sampleCard(id = 7L),
            onClick = { clicked = it },
        )
    }

    composeRule.onNodeWithTag(TestTags.Home.card(7L)).performClick()

    assertThat(clicked).isEqualTo(7L)
}
```

### 「発火しないこと」のテストを必ず書く

正常系より優先度が高いものが 2 つある。

```kotlin
@Test
fun `save is disabled and emits nothing when the date is in the past`() {
    var saved = false
    composeRule.setContent {
        EntryScreen(state = invalidDateState(), onSave = { saved = true })
    }

    composeRule.onNodeWithTag(TestTags.Entry.SAVE_BUTTON)
        .assertIsNotEnabled()
        .performClick()

    assertThat(saved).isFalse()
}
```

```kotlin
/**
 * 「まだ解約していない」を選んだとき、状態を何も変えないこと。
 * これが壊れるとリマインダーが止まり、ユーザーが課金される ——
 * このアプリで最悪の失敗モード。
 */
@Test
fun `choosing not-yet leaves the subscription active`() {
    var confirmed = false
    composeRule.setContent {
        CancelledDialog(onConfirmed = { confirmed = true }, onNotDone = {})
    }

    composeRule.onNodeWithTag(TestTags.Detail.CANCELLED_DIALOG_NO).performClick()

    assertThat(confirmed).isFalse()
}
```

---

## 4. 描画単位テストの書き方

描画状態マトリクスの 1 行が 1 テスト。
**「出るもの」と「出てはいけないもの」を必ずセットで書く。**

```kotlin
@Test
fun `loading shows skeletons and no cards`() {
    composeRule.setContent { HomeScreen(state = HomeUiState.Loading) }

    composeRule.onNodeWithTag(TestTags.Home.SKELETON).assertIsDisplayed()
    composeRule.onNodeWithTag(TestTags.Home.LIST).assertDoesNotExist()
    composeRule.onNodeWithTag(TestTags.Home.EMPTY_STATE).assertDoesNotExist()
}
```

`assertIsDisplayed()` だけのテストは、**Loading → Content でスケルトンが
残っていても通ってしまう**。消えるべきものが消えたことを見るのが要点。

### 解約 URL の有無による出し分け

```kotlin
@Test
fun `a service without a cancel url shows steps instead of the button`() {
    composeRule.setContent { DetailScreen(state = manualOnlyState()) }

    composeRule.onNodeWithTag(TestTags.Detail.MANUAL_STEPS).assertIsDisplayed()
    composeRule.onNodeWithTag(TestTags.Detail.CANCEL_BUTTON).assertDoesNotExist()
}
```

### 免責文は常に出ること

ストアポリシー上の要件なので、**両方の状態でテストする**。

```kotlin
@Test
fun `the disclaimer is always present`() {
    listOf(withUrlState(), manualOnlyState()).forEach { state ->
        composeRule.setContent { DetailScreen(state = state) }
        composeRule.onNodeWithTag(TestTags.Detail.DISCLAIMER).assertIsDisplayed()
    }
}
```

---

## 5. 時刻に依存するテスト

**実時間を待たない。** `Clock` を注入し、テストでは固定時刻を差し替える。

```kotlin
@Test
fun `crossing the 3 hour mark switches to the critical style`() {
    val renewal = Instant.parse("2026-08-07T12:00:00Z")

    val soon = urgencyOf(renewal, now = renewal.minus(Duration.ofHours(4)))
    val critical = urgencyOf(renewal, now = renewal.minus(Duration.ofHours(2)))

    assertThat(soon).isEqualTo(Urgency.SOON)
    assertThat(critical).isEqualTo(Urgency.CRITICAL)
}
```

`urgencyOf(renewalAt, now)` が `now` を引数で受け取る設計なのはこのため
（`:core:detection` で実装・テスト済み）。

---

## 6. 3 言語のテスト

全画面を 3 言語で撮り直すのは費用対効果が悪い。**書式が絡むところだけ**にしぼる。

| 対象 | 理由 |
|---|---|
| 金額表示 | 通貨記号と小数桁がロケールで変わる。`¥15.49` のような事故が起きる箇所 |
| 日付表示 | 形式がロケールで変わる |
| ボタンの文字列 | 英語は日本語の 1.5〜2 倍の長さになり、折り返しが崩れやすい |

```kotlin
@Test
fun `money never shows the wrong symbol for the locale`() {
    val usd = Money(1549, "USD")
    assertThat(usd.format(Locale.JAPAN)).contains("15.49")
    assertThat(usd.format(Locale.JAPAN)).doesNotContain("￥15")
}
```

---

## 7. 運用

**「改修のつどテスト完了」を守るために、落ちたまま進まない。**

- 新しいコンポーネントを足したら、イベント仕様表に行を足してからテストを書く
- `UiState` を足したら、描画状態マトリクスに行を足す
- 仕様（Artifact）と `TestTags.kt` と実装がずれたら、**仕様を先に直す**

CI では次を必須にする。

```bash
./gradlew :core:detection:test        # ロジック層（すでに動く）
./gradlew :app:testDebugUnitTest      # パーツ・画面（Robolectric に移行できた分）
./gradlew :app:connectedDebugAndroidTest   # 端末が要る分
```
