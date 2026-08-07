package app.subguard.data.local

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * SQLCipher のパスフレーズを管理する。
 *
 * 生成した乱数を [EncryptedSharedPreferences] に置く。その暗号鍵自体は
 * Android Keystore に格納され、アプリのプロセス外からは取り出せない。
 * つまり DB ファイルを吸い出されても、同じ端末の同じアプリでなければ開けない。
 *
 * バックアップは manifest 側で無効化してある。パスフレーズは端末をまたげないため、
 * DB だけ復元されても開けず、「復元しても使えない DB」がユーザーの手元に残ってしまう。
 */
class DatabasePassphrase(private val context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * パスフレーズを取得する。初回は生成して保存する。
     *
     * 戻り値の [ByteArray] は SQLCipher に渡した後、呼び出し側が破棄する。
     * SQLCipher は渡されたバイト列をゼロ埋めするため、使い回してはいけない。
     */
    @Synchronized
    fun getOrCreate(): ByteArray {
        prefs.getString(KEY_PASSPHRASE, null)?.let { encoded ->
            return Base64.decode(encoded, Base64.NO_WRAP)
        }

        val generated = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_PASSPHRASE, Base64.encodeToString(generated, Base64.NO_WRAP))
            .commit()   // apply() だと、この直後にプロセスが死ぬと鍵を失って DB が開けなくなる
        return generated
    }

    private companion object {
        const val PREFS_NAME = "subguard_secure"
        const val KEY_PASSPHRASE = "db_passphrase"
        const val PASSPHRASE_BYTES = 32
    }
}
