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

**設計フェーズ。実装コードはまだ存在しない。**

このリポジトリには現在、実装に着手するための設計ドキュメントと解約URLデータベースのみが入っている。
Phase 1 の実装は、[docs/roadmap.md](docs/roadmap.md) のタスク分解から着手する。

- **UI モックアップ（8画面・レンダリング済み）**:
  https://claude.ai/code/artifact/751ba2ad-b7d4-45d7-b4cb-2acd229bf41a

---

## ドキュメント

| ドキュメント | 内容 |
|---|---|
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
# Phase 1 着手時に Gradle プロジェクトを生成する
```

> ⚠️ 依存ライブラリのバージョンは **まだ実機ビルドで検証されていない**。
> 最初にプロジェクトを作る際は [docs/build-verification.md](docs/build-verification.md) の
> 手順で実際に解決・ビルドを通し、その結果を同ファイルと [docs/architecture.md](docs/architecture.md) に反映すること。

---

## ライセンス

未定。配布形態（クローズドソースでのストア配布を想定）が固まった段階で決める。
