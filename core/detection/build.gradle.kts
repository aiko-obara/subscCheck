import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// このモジュールは Android に一切依存しない。
// 依存を足すときは「JVM だけで動くか」を必ず確認すること。
// ここが純粋である限り、検知ロジックの回帰はエミュレータなしで秒単位で回せる。
// （このアプリで最も壊れやすいのが検知ロジックなので、これが効く）

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
}

/**
 * 実データのカタログをテストリソースに取り込む。
 *
 * テスト用に別のコピーを持たない。data/cancel_urls.json を唯一の情報源にすることで、
 * 「パターンを直したのにテストは古いコピーを見ていた」という事故を防ぐ。
 * :app 側も同じファイルを assets にコピーする。
 */
// rootProject 相対ではなくモジュール相対で参照する。
// 検証用に別の settings.gradle.kts からこのモジュールだけを include することがあり、
// そのとき rootProject は別の場所を指してしまうため。
val catalogFile = layout.projectDirectory.file("../../data/cancel_urls.json").asFile

tasks.named<ProcessResources>("processTestResources") {
    doFirst {
        check(catalogFile.isFile) { "catalog not found: $catalogFile" }
    }
    from(catalogFile) {
        into("catalog")
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
