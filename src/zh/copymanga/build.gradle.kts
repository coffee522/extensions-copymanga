import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

android {
    sourceSets {
        named("test") {
            java.directories.add("test")
            kotlin.directories.add("test")
        }
    }
}

tasks.matching { it.name.startsWith("ksp") && it.name.contains("UnitTest") }.configureEach {
    enabled = false
}

keiyoushi {
    name = "CopyManga"
    versionCode.set(
        providers.gradleProperty("extensionReleaseVersionCode").map { it.toInt() }.orElse(8),
    )
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        name = "拷贝漫画"
        lang = "zh"
        baseUrl = "https://www.mangacopy.com"
        id = 6696312508930833206L
    }

    deeplink {
        host("mangacopy.com")
        host("www.mangacopy.com")
        path("/comic/..*")
    }
}

dependencies {
    implementation("com.luhuiguo:chinese-utils:1.0")

    testImplementation(libs.bundles.common)
    testImplementation(libs.tachiyomi.lib.v16)
    testImplementation(libs.junit)
}
