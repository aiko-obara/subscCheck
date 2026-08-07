package app.subguard.data.remote

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private val Context.catalogDataStore by preferencesDataStore(name = "catalog_sync")

/**
 * カタログ同期の状態。バージョン・ETag・取得済み JSON 本文を保持する。
 *
 * JSON 本文をそのまま持つのは、検知パターンをリレーショナルに分解する意味がないから。
 * サービス一覧は検索するので DB にも入れるが、パターンは丸ごと読むだけ。
 */
@Singleton
class CatalogSyncStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.catalogDataStore

    suspend fun version(): Int =
        store.data.first()[KEY_VERSION] ?: 0

    suspend fun setVersion(value: Int) {
        store.edit { it[KEY_VERSION] = value }
    }

    suspend fun versionEtag(): String? = store.data.first()[KEY_VERSION_ETAG]

    suspend fun setVersionEtag(value: String?) {
        store.edit { prefs ->
            if (value == null) prefs.remove(KEY_VERSION_ETAG) else prefs[KEY_VERSION_ETAG] = value
        }
    }

    suspend fun catalogEtag(): String? = store.data.first()[KEY_CATALOG_ETAG]

    suspend fun setCatalogEtag(value: String?) {
        store.edit { prefs ->
            if (value == null) prefs.remove(KEY_CATALOG_ETAG) else prefs[KEY_CATALOG_ETAG] = value
        }
    }

    suspend fun storedCatalogJson(): String? = store.data.first()[KEY_CATALOG_JSON]

    suspend fun setStoredCatalogJson(value: String) {
        store.edit { it[KEY_CATALOG_JSON] = value }
    }

    private companion object {
        val KEY_VERSION = intPreferencesKey("version")
        val KEY_VERSION_ETAG = stringPreferencesKey("version_etag")
        val KEY_CATALOG_ETAG = stringPreferencesKey("catalog_etag")
        val KEY_CATALOG_JSON = stringPreferencesKey("catalog_json")
    }
}
