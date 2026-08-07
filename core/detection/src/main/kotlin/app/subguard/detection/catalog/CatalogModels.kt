package app.subguard.detection.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `data/cancel_urls.json` のモデル。
 *
 * 検知パターンをコードではなくデータとして持つのは、各社の通知フォーマットが
 * 予告なく変わるため。パターンを直せばアプリ更新なしで全ユーザーに配信できる。
 * 詳細は docs/cancel-url-database.md を参照。
 */
@Serializable
data class Catalog(
    val version: Int,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("updated_at") val updatedAt: String? = null,
    val services: List<CatalogService> = emptyList(),
    @SerialName("listener_packages") val listenerPackages: List<ListenerPackage> = emptyList(),
    @SerialName("detection_patterns") val detectionPatterns: List<DetectionPattern> = emptyList(),
) {
    /** 監視対象パッケージ名の集合。通知の足切りに使う。 */
    val watchedPackages: Set<String> by lazy {
        listenerPackages.map { it.packageName }.toSet()
    }

    companion object {
        /**
         * このアプリが理解できる `schema_version` の上限。
         *
         * 将来スキーマを壊す変更をしたとき、古いアプリが新しい JSON を
         * パースして壊れるのを防ぐ。同期処理はこの値を超える JSON を破棄する。
         */
        const val SUPPORTED_SCHEMA_VERSION = 1

        val json: Json = Json {
            ignoreUnknownKeys = true   // `_comment` など運用用のキーを無視する
            isLenient = false
        }

        fun parse(text: String): Catalog = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class CatalogService(
    val id: String,
    val names: Map<String, String> = emptyMap(),
    val regions: List<String> = emptyList(),
    @SerialName("cancel_url") val cancelUrl: String? = null,
    @SerialName("cancel_url_overrides") val cancelUrlOverrides: Map<String, String> = emptyMap(),
    @SerialName("manual_steps") val manualSteps: Map<String, List<String>>? = null,
    val keywords: List<String> = emptyList(),
    val icon: String? = null,
    val verified: Boolean = false,
    @SerialName("last_checked") val lastChecked: String? = null,
    val notes: String = "",
) {
    /** 言語コードに対応する表示名。未定義なら英語、それもなければ id。 */
    fun displayName(language: String): String =
        names[language] ?: names["en"] ?: id

    /** 言語コードに対応する解約 URL。ロケール別の上書きを優先する。 */
    fun cancelUrlFor(language: String): String? =
        cancelUrlOverrides[language] ?: cancelUrl

    /**
     * 解約 URL を持たず、アプリ内でしか解約できないサービスか。
     * 中華圏のサービスに多い。UI はボタンの代わりに手順リストを出す。
     */
    val isManualOnly: Boolean get() = cancelUrl == null
}

@Serializable
data class ListenerPackage(
    @SerialName("package") val packageName: String,
    val label: String = "",
    val regions: List<String> = emptyList(),
    val verified: Boolean = false,
    val notes: String = "",
)

/** 検知パターンの種別。 */
@Serializable
enum class PatternKind {
    /** 無料体験の開始。`service` / `trialDays` を取る。 */
    TRIAL_START,

    /** 更新・請求の予告。`date` を取る。 */
    RENEWAL_NOTICE,

    /** 金額。`amount` を取る。 */
    AMOUNT,
}

/**
 * 通知テキストに当てる 1 つのパターン。
 *
 * 名前付きキャプチャグループは**規約**で決まっている。マッピング表は持たない。
 *  - `service`   … サービス名
 *  - `trialDays` … 無料期間の日数
 *  - `date`      … 絶対日付
 *  - `amount`    … 金額（メジャー単位の文字列）
 */
@Serializable
data class DetectionPattern(
    val id: String,
    val kind: PatternKind,
    val regex: String,
    val locales: List<String> = emptyList(),
    val packages: List<String> = emptyList(),
    val confidence: Double = 0.5,

    /** [PatternKind.AMOUNT] のとき、このパターンが示す通貨。 */
    val currency: String? = null,

    /**
     * 通貨記号が曖昧か。`¥` は日本円と人民元の両方で使われるため、
     * このフラグが立っているパターンの結果は追加の文脈で確定させる必要がある。
     */
    @SerialName("ambiguous_symbol") val ambiguousSymbol: Boolean = false,

    /**
     * 「初月無料」「first month free」のように日数ではなく月数で表される
     * トライアルの月数。`trialDays` が取れないパターンで使う。
     */
    @SerialName("relative_months") val relativeMonths: Int? = null,

    val verified: Boolean = false,
    val notes: String = "",
) {
    /**
     * コンパイル済みの [Regex]。生成コストが高いので 1 度だけ作る。
     *
     * 大文字小文字とユニコードの正規化を有効にする。
     */
    val compiled: Regex by lazy {
        Regex(regex, setOf(RegexOption.IGNORE_CASE))
    }

    fun appliesTo(packageName: String, language: String): Boolean {
        val packageOk = packages.isEmpty() || packageName in packages
        val localeOk = locales.isEmpty() || language in locales
        return packageOk && localeOk
    }
}
