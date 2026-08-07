# SubGuard

**無料トライアルの解約忘れを防ぐ Android アプリ** — 日本語・英語・簡体字中国語対応。

3日間無料体験や初月無料のサブスクは、解約を忘れた瞬間に課金が始まる。SubGuard は
決済アプリやストアの通知から体験終了日を自動で読み取り、締切前にリマインドし、
**公式の解約ページへワンタップで運ぶ**。

> SubGuard は解約を自動代行するツールではない。ユーザー自身が解約するための
> **リマインダーと、公式解約ページへのナビゲーション**である。この位置づけは
> ストア掲載文・アプリ内文言・実装のすべてで一貫させる（→ [policy-compliance.md](docs/policy-compliance.md)）。

---

## 現在のステータス

**Phase 1（検知エンジンとデータ層）まで実装済み。UI と課金は未着手。**

| モジュール | 状態 | 検証 |
|---|---|---|
| `:core:detection` | 実装済み | ✅ **69 テスト通過**（`gradle :core:detection:test`） |
| `:app` データ層（Room / 同期 / 通知検知 / WorkManager） | 実装済み | ⚠️ **未ビルド**（下記） |
| `:app` UI（Compose） | 未着手（Phase 2） | — |
| 課金（Play Billing） | 未着手（Phase 3） | — |

> ⚠️ **`:app` はまだ一度もビルドされていない。**
> 設計・実装を行った環境から Google のホスト（`dl.google.com` / `maven.google.com`）に
> 到達できず、Android SDK も AndroidX の依存解決も不可能だった。
> `:core:detection` は Maven Central だけで完結するため実際にテストを通してある。
> 詳細と、ローカルでビルドを通すための手順は
> [docs/build-verification.md](docs/build-verification.md) を参照。

- **UI 確定仕様（操作できるプロトタイプ＋テスト仕様）**:
  https://claude.ai/code/artifact/aa1acf63-f2dc-4b74-94bb-e18eb468bce5
- ビジュアル方向性の初期モック（参考）:
  https://claude.ai/code/artifact/751ba2ad-b7d4-45d7-b4cb-2acd229bf41a

---

## ドキュメント

| ドキュメント | 内容 |
|---|---|
| [docs/ui-spec.md](docs/ui-spec.md) | **UI 確定仕様とテスト方針**（testTag 規約・描画状態・イベント一覧） |
| [docs/architecture.md](docs/architecture.md) | レイヤ構成、DI、依存バージョン、画面遷移 |
| [docs/data-model.md](docs/data-model.md) | Room スキーマ、暗号化、マイグレーション方針 |
| [docs/notification-detection.md](docs/notification-detection.md) | `NotificationListenerService` と正規表現抽出エンジン |
| [docs/reminder-and-navigation.md](docs/reminder-and-navigation.md) | WorkManager によるリマインダー、Custom Tabs 遷移 |
| [docs/cancel-url-database.md](docs/cancel-url-database.md) | 解約URL DB の JSON スキーマと同期方式 |
| [docs/monetization.md](docs/monetization.md) | Play Billing、無料枠1件、¥100 買い切り |
| [docs/i18n-and-aso.md](docs/i18n-and-aso.md) | 3言語リソース方針、ASO キーワード |
| [docs/oem-battery-optimization.md](docs/oem-battery-optimization.md) | 中国製端末等でのプロセス強制終了対策 |
| [docs/policy-compliance.md](docs/policy-compliance.md) | Google Play ポリシー適合（**最大のリスク**） |
| [docs/roadmap.md](docs/roadmap.md) | Phase 1〜4 のタスク分解と DoD |
| [docs/build-verification.md](docs/build-verification.md) | ⚠️ バージョン行列の検証状況（**未検証**） |

**データ**

| ファイル | 内容 |
|---|---|
| [data/cancel_urls.json](data/cancel_urls.json) | 解約URL・検知パターン・監視対象パッケージのマスタ |

---

## 設計の背骨となっている3つの決定

**1. 残り時間が唯一の視覚変数**
一覧の並び順、カードの色、通知の緊急度、すべてを `renewal_date` から導出する。
ソート UI を持たず、緊急度を DB に保存しない（時刻経過で必ず陳腐化するため）。

**2. サーバーを持たない**
サブスクデータは端末内の暗号化 Room DB のみ。通知の読み取り内容は端末外に出ない。
これはプライバシー方針であると同時に、Google Play の通知アクセスポリシーへの適合要件でもある。

**3. 自動検知は、勝手に登録しない**
通知の解析結果は `detection_events` に PENDING 状態で積むだけ。ユーザーが確認画面で承認して
初めて `subscriptions` に入る。誤検知が「存在しないサブスクの通知」を生むのを構造的に防ぐ。

---

## 開発環境

| 項目 | 値 |
|---|---|
| JDK | 17 以上（開発機では 21 を推奨） |
| Gradle | 8.14 以上 |
| Android SDK | compileSdk / targetSdk **36**、minSdk **26** |
| IDE | Android Studio |

```bash
git clone https://github.com/aiko-obara/subscCheck.git
cd subscCheck

# 検知エンジンのテスト（Android SDK 不要。Maven Central だけで動く）
./gradlew :core:detection:test

# アプリ本体（Android SDK と Google Maven への到達が必要）
./gradlew :app:assembleDebug
```

### モジュール構成

```
:core:detection   Android 非依存の検知エンジン。JVM テストで回帰を担保する
:app              Android アプリ本体
data/             解約URL・検知パターンのマスタ（唯一の情報源）
```

`data/cancel_urls.json` は 1 ファイルで 2 箇所に配られる ——
`:core:detection` のテストリソースと、`:app` の assets。
テスト用のコピーを別に持たないことで、「パターンを直したのにテストは古いコピーを見ていた」
という事故を防いでいる。

> ⚠️ 依存ライブラリのバージョンは **まだ実機ビルドで検証されていない**。
> 最初にプロジェクトを作る際は [docs/build-verification.md](docs/build-verification.md) の
> 手順で実際に解決・ビルドを通し、その結果を同ファイルと [docs/architecture.md](docs/architecture.md) に反映すること。

---

## ライセンス

未定。配布形態（クローズドソースでのストア配布を想定）が固まった段階で決める。
