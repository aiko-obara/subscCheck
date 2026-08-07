# ビルド検証ステータス

> **結論: この設計ドキュメントに記載された依存バージョンは、まだ一度も検証されていない。**
> 実装に着手する開発者は、コードを書き始める前に本ドキュメントの手順を実行し、
> 実測値で [architecture.md](architecture.md) のバージョン行列を上書きすること。

---

## 1. なぜ検証できなかったか

設計を行ったサンドボックス環境から、**Google のホストへの egress がポリシーで拒否された**。

| ホスト | 用途 | 結果 |
|---|---|---|
| `dl.google.com` | Android SDK command-line tools の配布元 | **HTTP 403（ポリシー拒否）** |
| `maven.google.com` | AndroidX / AGP / Room / Compose / Hilt / Play Billing | **HTTP 403**（`dl.google.com/dl/android/maven2` へ 301 リダイレクトするため同じ拒否） |
| `repo1.maven.org` | Maven Central | HTTP 200（到達可） |
| `services.gradle.org` | Gradle ディストリビューション | HTTP 200（到達可） |
| `plugins.gradle.org` | Gradle Plugin Portal | HTTP 200（到達可） |

Google Maven が到達不可のため、**Android プロジェクトのビルドはもちろん、依存の解決すら不可能**だった。
Maven Central 側だけが生きていても、`androidx.*` と `com.android.tools.build:gradle` は
Google Maven にしか存在しないため、代替経路はない。

環境のプロキシ運用方針により、403（組織のegressポリシー拒否）は迂回もリトライも行っていない。

検証に使った確認コマンド:

```bash
curl -sS "$HTTPS_PROXY/__agentproxy/status"     # recentRelayFailures に dl.google.com:443 の拒否が記録される
curl -sSL -o /dev/null -w "%{http_code} %{url_effective}\n" \
  https://maven.google.com/androidx/room/room-runtime/maven-metadata.xml
```

## 2. 検証できたこと

環境そのものは Android 開発の前提を満たしている。足りないのはネットワーク許可だけ。

| 項目 | 実測値 |
|---|---|
| JDK | OpenJDK **21.0.10** (`/usr/lib/jvm/java-21-openjdk-amd64`) |
| Gradle | **8.14.3** (`/opt/gradle/bin/gradle`)、バンドル Kotlin 2.0.21 |
| Android SDK | **未インストール**（`ANDROID_HOME` 未設定、`sdkmanager` / `adb` なし） |

### 2-1. 検知パターンの検証（実施済み・合格）

`data/cancel_urls.json` の `detection_patterns` は Android SDK なしで検証できるため、
**素の JDK で実際にコンパイルと動作を確認した**。

| 検証 | 結果 |
|---|---|
| 全12パターンが `java.util.regex.Pattern` でコンパイルできるか | **12 / 12 合格** |
| 名前付きキャプチャグループが有効か | 合格（Java の命名規則に適合） |
| 想定テキストにマッチするか（正例18件） | **18 / 18 合格** |
| 非サブスク通知にマッチしないか（負例2件） | **2 / 2 合格** |

`Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE` で実行。
Kotlin の `Regex` は `java.util.regex` のラッパーなので、この結果はそのまま通用する。

負例に使ったテキスト（誤検知しないことを確認した）:

- `ご注文の商品が発送されました`
- `Your package has been delivered`

### 2-2. 検証で見つかった既知の問題

**`play_trial_start_en` のサービス名キャプチャが貪欲すぎる。**

```
入力: "Your 7-day free trial of Adobe Creative Cloud has started"
現状の service グループ: "Adobe Creative Cloud has started"   ← 末尾を巻き込んでいる
期待:                     "Adobe Creative Cloud"
```

`(?:of|for)\s+(?<service>.{1,40})` の `.{1,40}` が行末まで取ってしまう。
Phase 1 で fixture を整備する際に、次のいずれかで修正すること。

- 末尾の定型句（`has started`, `begins`, `is active` 等）を除外する後読みを足す
- サービス名の抽出を専用パターンに分離し、トライアル検知とは別に走らせる
  （→ [notification-detection.md](notification-detection.md) 4章「1つのパターンで全部を取ろうとしない」）

なお、抽出結果はユーザーの確認画面を経由するため、この不具合が
そのまま誤登録になることはない。ただし確認画面の初期値が汚れるので直すべき。

### 2-3. `data/cancel_urls.json` の構造検証（実施済み・合格）

| 検証 | 結果 |
|---|---|
| JSON として妥当か (`jq empty`) | 合格 |
| `services[].id` が一意か | 合格（27件すべて一意） |
| `cancel_url` が null のエントリに `manual_steps` があるか | 合格（該当なし = 全件が代替手順を持つ） |

収録数: サービス 27、検知パターン 12、監視パッケージ 4。

> **ただし URL の内容は未検証。** 上記は構造の検証であって、
> 各 URL が実際に解約導線に着くかは確認していない（全件 `verified: false`）。
> リリース前の人手検証が必須（→ [roadmap.md](roadmap.md) タスク 4-1）。

---

## 3. 実装着手時にやること

`dl.google.com` と `maven.google.com` に到達できる環境（ローカルの Android Studio、
または当該ホストを許可した CI）で以下を行う。

### 3-1. SDK の導入

```bash
# Android Studio を使う場合は SDK Manager から入れれば足りる
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

### 3-2. バージョン行列の実測

[architecture.md](architecture.md) の「依存バージョン候補」表をそのまま
`gradle/libs.versions.toml` に写し、次を実行する。

```bash
./gradlew :app:dependencies --configuration debugRuntimeClasspath
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

### 3-3. 特に噛み合わせを確認すべき組み合わせ

バージョンずれで最も壊れやすい箇所。ここが通れば残りはほぼ通る。

1. **Kotlin ↔ KSP** — KSP のバージョンは Kotlin のバージョンに 1:1 で対応している
   （`2.x.y-1.0.z` の `2.x.y` 部分が Kotlin 版と完全一致していないと起動時に失敗する）。
2. **Kotlin ↔ Compose Compiler** — Kotlin 2.0 以降は Compose Compiler が Kotlin 本体に統合され、
   `org.jetbrains.kotlin.plugin.compose` プラグインを Kotlin と同じバージョンで適用する方式に変わった。
   旧来の `composeOptions { kotlinCompilerExtensionVersion }` は使わない。
3. **AGP ↔ Gradle** — AGP は要求 Gradle 版の下限を持つ。Gradle Wrapper を AGP の要求に合わせる。
4. **AGP ↔ JDK** — AGP 8.x 系は JDK 17 以上を要求する。
5. **Room ↔ KSP** — Room は KAPT ではなく KSP を使う（`ksp("androidx.room:room-compiler:…")`）。
6. **Hilt ↔ KSP** — Hilt も KSP 対応版を使う。WorkManager への注入には
   `androidx.hilt:hilt-work` と `hilt-compiler` が別途必要。
7. **SQLCipher ↔ Room** — `net.zetetic:sqlcipher-android`（旧 `android-database-sqlcipher` の後継）と
   `androidx.sqlite:sqlite` の組み合わせで `SupportOpenHelperFactory` を差し込む。
   Room のバージョンによって `SupportSQLiteOpenHelper` の API が変わるため、ここは必ず実機で確認する。

### 3-4. 結果の記録

実測が終わったら、本ドキュメントの第1章を削除し、以下を書き残す。

- 実際に `assembleDebug` が通ったバージョンの完全な表
- 失敗した組み合わせと、その症状（後続の開発者が同じ罠を踏まないため）
- `./gradlew --version` と `sdkmanager --list_installed` の出力

---

## 4. 検証されていないバージョンの扱い

[architecture.md](architecture.md) に記載しているバージョンは、
**「この時期に存在したはずの安定版」という推定に基づく候補**であって、実測値ではない。

そのため、実装着手時には次のどちらかを取ること。

- **推奨**: Android Studio の新規プロジェクトウィザードで空プロジェクトを生成し、
  IDE が入れた AGP / Kotlin / KSP のバージョンを起点にする。ウィザードは必ず整合の取れた組み合わせを入れる。
  その上で Room / Hilt / Billing など個別ライブラリを追加していく。
- あるいは、architecture.md の候補をそのまま入れてビルドし、壊れた箇所だけを直す。

いずれの場合も、**確定した実測値でドキュメントを上書きすること**。
推定値がそのまま残り続けるのが、この種のドキュメントの一番よくある腐り方である。
