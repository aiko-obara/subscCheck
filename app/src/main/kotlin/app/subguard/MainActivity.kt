package app.subguard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import dagger.hilt.android.AndroidEntryPoint

/**
 * 単一 Activity。画面遷移は Compose Navigation で行う。
 *
 * **Phase 2 で中身を実装する。** 現時点では Phase 1（検知エンジンとデータ層）までが
 * 実装済みで、UI はプレースホルダ。画面設計は docs/roadmap.md の Phase 2 と
 * UI モックアップを参照。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 通知からの遷移先。Phase 2 でナビゲーションに渡す。
        val subscriptionId = intent?.getLongExtra(EXTRA_SUBSCRIPTION_ID, -1L) ?: -1L
        val detectionEventId = intent?.getLongExtra(EXTRA_DETECTION_EVENT_ID, -1L) ?: -1L

        setContent {
            MaterialTheme {
                Surface {
                    Text(
                        text = when {
                            subscriptionId >= 0 -> "Subscription #$subscriptionId"
                            detectionEventId >= 0 -> "Detection #$detectionEventId"
                            else -> getString(R.string.app_name)
                        },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_SUBSCRIPTION_ID = "subscription_id"
        const val EXTRA_DETECTION_EVENT_ID = "detection_event_id"
    }
}
