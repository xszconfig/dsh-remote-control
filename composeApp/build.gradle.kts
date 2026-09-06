import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.detekt)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // iOS 目标留待后续扩展（MVP 只跑 android）。取消注释即可启用：
    // listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { t ->
    //     t.binaries.framework { baseName = "ComposeApp"; isStatic = true }
    // }

    sourceSets {
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.zxing.android.embedded)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.multiplatform.markdown.renderer)
            implementation(libs.multiplatform.markdown.renderer.m3)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "com.daniel.dshremote"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.daniel.dshremote"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
        // 内部测试装机包：R8 压缩 + 资源裁剪 + debug 签名（可直接 adb install），仅 arm64-v8a
        create("release-in-house") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
        // 线上商店包：R8 压缩 + 资源裁剪；签名由发布时（Play App Signing）配置，此处占位不签
        create("release-store") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}

// ── lint（detekt）────────────────────────────────────────────────────────────
// 全量 detekt 与 P0 闸门都只做「显式任务」，不接入 check 生命周期：
// 存量代码存在大量风格级告警，接入 check 会阻塞日常构建（详见 AGENTS.md）。
// P0 阈值起步宽松（LongMethod 200 / LargeClass 1200 / LongParameterList 8/10），
// 收紧路径见 docs/lint-rules.md。
detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = false
    source.setFrom(fileTree("src") { include("**/*.kt") })
}

// 全量 detekt 输出 txt 报告（逐条问题），供 scripts/lint.sh 打印
tasks.named<Detekt>("detekt") {
    reports {
        txt.required.set(true)
        txt.outputLocation.set(rootProject.layout.buildDirectory.file("reports/detekt/detekt.txt"))
        xml.required.set(false)
        html.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
}

// P0 闸门任务：只跑超大函数/类/参数列表等高风险规则，任何命中即失败（exit != 0）
tasks.register<Detekt>("detektP0") {
    description = "只跑 P0 高风险规则（LongMethod/LargeClass/LongParameterList），命中即失败"
    group = "verification"
    config.setFrom(rootProject.files("config/detekt/detekt-p0.yml"))
    buildUponDefaultConfig = false
    setSource(fileTree("src") { include("**/*.kt") })
    reports {
        txt.required.set(true)
        txt.outputLocation.set(rootProject.layout.buildDirectory.file("reports/detekt/detektP0.txt"))
        xml.required.set(false)
        html.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
}

// detekt 全量不进 check 生命周期：避免存量风格告警阻塞构建；P0 闸门由 pre-commit hook 强制
tasks.named("check").configure {
    setDependsOn(
        dependsOn.filterNot { dep ->
            val name = when (dep) {
                is TaskProvider<*> -> dep.name
                is org.gradle.api.Task -> dep.name
                is String -> dep.substringAfterLast(':')
                else -> ""
            }
            name == "detekt"
        }
    )
}
