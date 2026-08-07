# 収益設計

**¥100 / $0.99 / ¥6 の非消費型インアプリ購入（買い切り）。無料枠は登録1件。**

サブスク解約を助けるアプリが月額課金を取るのは、それ自体が矛盾している。
買い切りであることは価格戦略であると同時に、**プロダクトの主張そのもの**。

## 1. 商品設計

| 項目 | 値 |
|---|---|
| 商品ID | `subguard_unlock_v1` |
| 種別 | 非消費型（Non-consumable）INAPP |
| 価格 | ¥100 / $0.99 / ¥6（各国のストア価格表に従う） |
| 提供内容 | 登録件数の無制限化 |

**商品IDに `_v1` を付ける理由**: Play Console の商品IDは一度作ると削除できず、
価格帯や提供内容を大きく変えたくなったときに新商品を作る必要が出る。
最初から連番の余地を残しておく。

## 2. 無料枠 = 登録1件

### この設計の狙い

無料枠で **自動検知と通知はフルに動く**。制限するのは登録件数だけ。

```
ユーザーの体験の順序
1. インストール、通知アクセスを許可
2. Netflix の無料体験を検知 → 登録（無料枠1件目）
3. 3時間前に通知が届く → 解約ページへ → 実際に解約できた
4. 「このアプリは効く」という実感が生まれる    ← ここが重要
5. 2件目（Adobe）を登録しようとする
6. ペイウォールが出る → ¥100 なら払う
```

**ペイウォールは「価値を証明した直後」にしか出ない。**
機能を先に見せて後から金を取る構造なので、CVR が高くなる。

逆に「自動検知が有料」にすると、ユーザーはアプリの価値を体験する前に
課金判断を迫られる。1件も守れていない状態で ¥100 を払う理由はない。

### 実装

```kotlin
// domain/usecase/CanAddSubscriptionUseCase.kt
class CanAddSubscriptionUseCase(
    private val subscriptions: SubscriptionRepository,
    private val entitlement: EntitlementRepository,
) {
    suspend operator fun invoke(): Result {
        if (entitlement.isUnlocked()) return Result.Allowed
        val count = subscriptions.countActive()
        return if (count < FREE_LIMIT) Result.Allowed else Result.NeedsUnlock(count)
    }
    companion object { const val FREE_LIMIT = 1 }
}
```

`FREE_LIMIT` は1箇所にしか書かない。無料枠の値は、リリース後に
データを見て調整したくなる可能性が高い（1件が厳しすぎるなら3件へ）。

### 制限のかけ方

| 対象 | 無料枠超過時の挙動 |
|---|---|
| 手動での新規登録 | ペイウォールを表示。登録は保留 |
| 検知の承認 | 同上。`detection_events` は PENDING のまま残る |
| 既存1件の閲覧・編集・通知 | **すべて通常どおり動く** |
| カタログ同期 | 制限なし |

**既存データを人質に取らない。** 無料枠を超えた状態でも、
すでに登録済みのものは完全に機能し続ける。

## 3. Play Billing の実装

### 購入状態の確認

```kotlin
// アプリ起動時とフォアグラウンド復帰時
billingClient.queryPurchasesAsync(
    QueryPurchasesParams.newBuilder()
        .setProductType(BillingClient.ProductType.INAPP)
        .build()
) { result, purchases ->
    val owned = purchases.any {
        it.products.contains(PRODUCT_ID) &&
        it.purchaseState == Purchase.PurchaseState.PURCHASED
    }
    // ...
}
```

### `acknowledgePurchase` を必ず呼ぶ

```kotlin
if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
    !purchase.isAcknowledged) {
    billingClient.acknowledgePurchase(
        AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
    ) { /* ... */ }
}
```

**購入から3日以内に承認しないと、Google が自動的に返金する。**
これは Play Billing で最も多い実装ミス。購入直後だけでなく、
`queryPurchasesAsync` の結果に未承認のものがあれば毎回承認を試みる。

### オフラインでのアンロック維持

`queryPurchasesAsync` はネットワークを要求する。機内モードや圏外で
アプリを開いたときにアンロックが外れると、ユーザーは「金を払ったのに使えない」と感じる。

```
購入確認に成功 → DataStore に isUnlocked = true をキャッシュ
起動時         → まず DataStore を読んでUIを確定させる
                → 裏で queryPurchasesAsync を実行し、結果で更新
```

**キャッシュを楽観的に信じる。** 不正利用のリスクはあるが、¥100 の商品で
そこに労力を割く価値はない。それより正規購入者の体験を守るほうが重要。

### 返金・失効した場合

`queryPurchasesAsync` が「所有していない」を返したら `isUnlocked = false` にする。
このとき **登録済みデータは絶対に消さない**。

2件目以降を読み取り専用にし、通知も継続する。
ユーザーが再購入すればそのまま編集できる状態に戻る。
データを消すのは、返金の正当性に関わらず、ユーザーにとって理不尽な罰になる。

### 「購入を復元」

非消費型 INAPP は、同じ Google アカウントなら機種変更後も
`queryPurchasesAsync` で自動的に復元される。それでも**ボタンを明示的に置く**。

自動復元が効いていても、ユーザーは「復元ボタンがない = 復元できない」と考える。
ボタンは `queryPurchasesAsync` を再実行して結果をトーストで返すだけでよい。

## 4. ペイウォールの文言

3言語で用意する。価格は**ハードコードせず、`ProductDetails` から取得した
`formattedPrice` を表示する**（ストアが各国通貨で整形済みの文字列を返す）。

| 要素 | ja | en | zh |
|---|---|---|---|
| 見出し | 2件目からはアンロックが必要です | Unlock to track more | 解锁后可添加更多订阅 |
| 価格の補足 | 買い切り · 月額なし | One-time · No subscription | 一次性付费 · 无月费 |
| 主ボタン | {price} でアンロック | Unlock for {price} | 以 {price} 解锁 |
| 副ボタン | 購入を復元 | Restore purchase | 恢复购买 |

**「買い切り・月額なし」を必ず明示する。** 解約忘れアプリが月額だったら
何の冗談かという話になるので、ここは強調して構わない。

## 5. ストアポリシーとの整合

| 論点 | 対応 |
|---|---|
| デジタルコンテンツの課金 | Play Billing を使用（必須）。外部決済への導線は一切置かない |
| 価格の表示 | `ProductDetails.formattedPrice` を使い、自前で通貨記号を組まない |
| 「解約代行」との誤認 | ストア説明・アプリ内文言で「リマインダーとナビゲーション」と明示 |
| 定期購入との混同 | 商品種別は INAPP（非消費型）。SUBS は使わない |

詳細は [policy-compliance.md](policy-compliance.md)。

## 6. 広告について

**初期リリースでは広告を入れない。**

- 広告SDKを入れると、データ安全性フォームで「データを収集する」と申告する必要が生じ、
  「サーバーを持たない・データを外に出さない」という本アプリ最大の訴求が崩れる。
- 通知アクセス権限を持つアプリに広告SDKが同居していると、
  ストア審査でのリスクが上がる。
- ¥100 の買い切りで十分に成立する設計にしてある。

将来、無料ユーザーからの収益化が必要になった場合でも、
広告より「無料枠の調整」を先に検討する。
