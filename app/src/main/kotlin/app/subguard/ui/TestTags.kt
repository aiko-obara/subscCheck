package app.subguard.ui

/**
 * Compose の `Modifier.testTag()` に渡す識別子。
 *
 * **画面側とテスト側の両方がここを参照する。** 文字列リテラルを 2 箇所に書かない。
 * 書くと、片方を直したときにテストが「存在しないノード」を探して落ちるか、
 * もっと悪いことに、消えたはずの要素を探し続けて通ってしまう。
 *
 * ## 命名規約
 *
 * ```
 * subguard:<screen>:<element>[:<id>]
 * ```
 *
 *  - `screen`  … 画面名
 *  - `element` … lowerCamelCase。**役割で名づける。** `cancelButton` は可、`redButton` は不可
 *  - `id`      … リスト要素のみ。エンティティの主キーをそのまま使う
 *
 * UI 仕様（操作できるプロトタイプとイベント一覧）:
 * https://claude.ai/code/artifact/aa1acf63-f2dc-4b74-94bb-e18eb468bce5
 */
object TestTags {

    private const val PREFIX = "subguard"

    object Home {
        private const val S = "$PREFIX:home"
        const val LIST = "$S:list"
        const val ADD_BUTTON = "$S:addButton"
        const val EMPTY_STATE = "$S:emptyState"
        const val SKELETON = "$S:skeleton"
        const val ACTION_BADGE = "$S:actionBadge"

        const val PERMISSION_BANNER = "$S:permissionBanner"
        const val PERMISSION_BANNER_ACTION = "$S:permissionBanner:action"
        const val PERMISSION_BANNER_DISMISS = "$S:permissionBanner:dismiss"

        const val ERROR_BANNER = "$S:errorBanner"
        const val ERROR_BANNER_RETRY = "$S:errorBanner:retry"

        /** リスト内のカード。id は `subscriptions.id`。 */
        fun card(id: Long) = "$S:card:$id"

        /** カード内のカウントダウン。書式の切り替わりを検証するため個別に持つ。 */
        fun countdown(id: Long) = "$S:card:$id:countdown"

        /** 無料枠を超えて登録できない行。 */
        fun lockedCard(id: Long) = "$S:lockedCard:$id"

        fun tab(name: String) = "$S:tab:$name"
    }

    object Detail {
        private const val S = "$PREFIX:detail"
        const val BACK = "$S:back"
        const val EDIT_BUTTON = "$S:editButton"
        const val COUNTDOWN_RING = "$S:countdownRing"

        /** 解約 URL があるときだけ存在する。ないときは [MANUAL_STEPS] が出る。 */
        const val CANCEL_BUTTON = "$S:cancelButton"

        /** アプリ内解約のみのサービスで出る手順リスト。 */
        const val MANUAL_STEPS = "$S:manualSteps"

        /** ストアポリシー対策の免責文。**常に存在すること**をテストする。 */
        const val DISCLAIMER = "$S:disclaimer"

        const val NOT_FOUND = "$S:notFound"

        const val CANCELLED_DIALOG = "$S:cancelledDialog"
        const val CANCELLED_DIALOG_YES = "$S:cancelledDialog:yes"
        const val CANCELLED_DIALOG_NO = "$S:cancelledDialog:no"
    }

    object Detection {
        private const val S = "$PREFIX:detection"
        const val BACK = "$S:back"
        const val CONFIRM_BUTTON = "$S:confirmButton"
        const val DISMISS_BUTTON = "$S:dismissButton"
        const val EMPTY_STATE = "$S:emptyState"

        /** confidence が低いときだけ出る警告。 */
        const val CONFIDENCE_WARNING = "$S:confidenceWarning"

        /** 通貨を確定できなかったときの選択肢。出ている間は登録ボタンが非活性。 */
        const val CURRENCY_PICKER = "$S:currencyPicker"
        fun currency(code: String) = "$S:currency:$code"

        fun field(name: String) = "$S:field:$name"
    }

    object Entry {
        private const val S = "$PREFIX:entry"
        const val BACK = "$S:back"
        const val SAVE_BUTTON = "$S:saveButton"

        fun field(name: String) = "$S:field:$name"
        fun fieldError(name: String) = "$S:field:$name:error"
        fun suggestion(serviceId: String) = "$S:suggestion:$serviceId"
    }

    object Paywall {
        private const val S = "$PREFIX:paywall"
        const val CLOSE = "$S:close"
        const val PURCHASE_BUTTON = "$S:purchaseButton"
        const val RESTORE_BUTTON = "$S:restoreButton"
        const val PRICE = "$S:price"
        const val ERROR = "$S:error"
    }

    object Onboarding {
        private const val S = "$PREFIX:onboarding"
        const val BACK = "$S:back"
        const val NEXT = "$S:next"

        /** **全ステップに存在すること**をテストする。権限を拒否しても使えるのが前提。 */
        const val SKIP = "$S:skip"

        fun step(index: Int) = "$S:step:$index"
    }

    object Settings {
        private const val S = "$PREFIX:settings"
        const val LOCK_SCREEN_TOGGLE = "$S:lockScreenToggle"
        const val OEM_GUIDE = "$S:oemGuide"
        const val LANGUAGE = "$S:language"
    }
}
