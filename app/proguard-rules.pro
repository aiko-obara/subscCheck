# kotlinx.serialization — シリアライザは実行時にリフレクションで引かれる
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.subguard.**$$serializer { *; }
-keepclasseswithmembers class app.subguard.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.subguard.detection.catalog.** { *; }
-keep,includedescriptorclasses class app.subguard.data.remote.CatalogVersion { *; }

# SQLCipher — ネイティブ側から参照される
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
