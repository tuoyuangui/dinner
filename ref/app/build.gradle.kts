import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.guolongli.dinner"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.guolongli.dinner"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 4
        versionName = "3.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    /**
     * JVM 单测里 android.jar 的方法是空壳，默认抛 "not mocked"。
     * 打开 returnDefaultValues 让 Log 之类的系统调用返回默认值变成空操作，
     * 这样纯逻辑（编解码、同步合并、清理判定）才能真正被测到。
     * 这是测试基础设施开关，不放宽任何断言。
     */
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // 系统 ~/.android 只读，无法自动生成 debug keystore；这里把 AGP 内置的 debug signingConfig 指向工作区 keystore。
    // release 签名材料由 scripts/create-release-keystore.sh 生成，登记在仓库根 keystore.properties（不入库）。
    // 没有该文件时 release 仍可构建，但产物是未签名包，无法直接安装。
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.isFile) {
            keystorePropsFile.inputStream().use { load(it) }
        }
    }
    val hasReleaseSigning = keystorePropsFile.isFile && !keystoreProps.getProperty("storeFile").isNullOrBlank()

    signingConfigs {
        getByName("debug") {
            storeFile = file("../.android-keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // Kotlin 2.2 起 `-Xjvm-default=all` 已弃用，改用稳定的 `-jvm-default=all`。
            // 语义相同（接口默认实现生成到 JVM default 方法里），只是不再走实验开关。
            freeCompilerArgs.add("-jvm-default=enable")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)

    // Persistence
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // Image
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.exifinterface)

    // Browser (Custom Tabs)：SDK 打开 Web 授权时仍复用系统浏览器能力，
    // 这条只用于「没有 Custom Tabs 时」的兜底说明页，不承载授权本身。
    implementation(libs.browser)

    // 百度 OAuth SDK 2.0.7（本地 aar，见 baidu-oauth-sdk-android-release-2.0.7/）。
    //
    // 用 fileTree 而不是 flatDir：AGP 9 下 flatDir 不再参与 aar 的 manifest / 资源合并，
    // SDK 自带的 WebViewActivity 与 OauthSdkTheme 就会漏进最终包，授权页一打开就崩。
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp)
    testImplementation(libs.mockwebserver)
    testImplementation(platform(libs.androidx.compose.bom))

    // Compose debugging / preview
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.junit)
}