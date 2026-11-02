import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * 百度网盘开放平台的应用信息。
 * 应用凭据（AppKey / SecretKey）**默认注入空串**，不从 local.properties 带进包里——
 * 每个用户在「网盘」页手动填写自己的凭据（运行期经 BaiduAuthStore 落盘）。
 * 应用名必须是 dinner —— 网盘接口只允许访问 /apps/dinner/ 下的文件。
 */

/**
 * 版本号。产物文件名（`apk/dinner-<版本>-debug.apk`）与 BuildConfig 都从这里取，
 * 免得改了 defaultConfig 忘了改脚本。
 */
val appVersionName = "1.0.0"

/**
 * 构建目录重定向：**只有本机需要，CI 与普通机器不设就走默认的 `app/build`。**
 *
 * 这台机器的工程放在 NTFS（fuseblk）分区上，aapt2 / utime 等文件属性操作会报
 * 「不允许的操作」，所以构建产物要落到 ext4 的用户缓存目录。做法是命令行
 * `-Pdinner.build.dir=...` 或环境变量 `DINNER_BUILD_DIR=...` 指定（`scripts/build.sh`
 * 会自动导出后者），**不能在这里写死绝对路径**——那会让 GitHub Actions 的
 * runner（没有 /home/liguolong）连配置阶段都过不去。
 */
val redirectedBuildDir = providers.gradleProperty("dinner.build.dir").orNull
    ?: providers.environmentVariable("DINNER_BUILD_DIR").orNull
if (!redirectedBuildDir.isNullOrBlank()) {
    layout.buildDirectory = file(redirectedBuildDir)
}

android {
    namespace = "com.guolongli.dinner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.guolongli.dinner"
        minSdk = 28
        targetSdk = 35
        versionCode = 10
        versionName = appVersionName
        vectorDrawables { useSupportLibrary = true }

        // 应用凭据默认为空，必须由用户在「网盘」页手动填写（见 BaiduAuthStore）
        buildConfigField("String", "BAIDU_APP_ID", "\"\"")
        buildConfigField("String", "BAIDU_APP_KEY", "\"\"")
        buildConfigField("String", "BAIDU_APP_SECRET", "\"\"")
        buildConfigField("String", "BAIDU_REDIRECT_URI", "\"oob\"")
    }

    /**
     * 调试签名固定用工程内的证书，而不是让 AGP 去 ~/.android 现生成一个。
     * 这样 CI / 无桌面环境（没有 debug.keystore 目录）也能直接出包，
     * 同一个证书的 APK 覆盖安装也不会因为签名变了而要求先卸载。
     */
    signingConfigs {
        create("localDebug") {
            storeFile = rootProject.file("tools/debug.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        /**
         * 本机出正式包用的签名。
         *
         * 用的还是工程里那张调试证书：release 包只是为了「去掉 .debug 后缀、能和 debug 包并存」
         * 方便自己安装验证，**不是上架证书**。真要上架请换成自己的 keystore，
         * 并且不要把 keystore 与口令提交进仓库。
         */
        create("localRelease") {
            storeFile = rootProject.file("tools/debug.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("localDebug")
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("localRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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

    testOptions {
        unitTests {
            // 网络 / 同步测试只碰 JVM，不依赖 Android 运行时
            isReturnDefaultValues = true
            // 启动冒烟测试要读真实的 manifest / 主题 / 字符串，
            // 否则 Robolectric 会退化成「只有 android.jar 的资源」，
            // 测出来的通过并不能代表真机
            isIncludeAndroidResources = true
        }
    }
}

ksp {
    // Room 导出 schema，便于后续写迁移
    arg("room.schemaLocation", "$projectDir/schemas")
}

/**
 * 单测用的 conscrypt native 库。
 *
 * Robolectric 起 Application 时会装一个 conscrypt 的 TLS Provider，它要先把
 * jar 里的 .so 解到临时目录、再 chmod +x 才能加载。本机的 seccomp 让 chmod
 * 类调用返回 EPERM，解压那一步必然失败，于是所有 Robolectric 用例在
 * setUpApplicationState 就挂了。把 .so 预先摊到磁盘上，让
 * ClassLoader.loadLibrary 直接命中 java.library.path，即可绕过解压。
 */
val testNativeDir = layout.buildDirectory.dir("test-native")
val extractTestNative = tasks.register("extractTestNative") {
    val output = testNativeDir.get().asFile
    outputs.dir(output)
    doLast {
        output.mkdirs()
        val classpath = project.configurations.getByName("debugUnitTestRuntimeClasspath")
        classpath.files
            .filter { it.name.startsWith("conscrypt") && it.name.endsWith(".jar") }
            .forEach { jar ->
                ZipFile(jar).use { zip ->
                    zip.entries().asSequence()
                        .filter { it.name.startsWith("META-INF/native/") && it.name.endsWith(".so") }
                        .forEach { entry ->
                            val name = entry.name.substringAfterLast('/')
                            zip.getInputStream(entry).use { input ->
                                output.resolve(name).outputStream().use { out -> input.copyTo(out) }
                            }
                        }
                }
            }
    }
}

tasks.withType<Test>().configureEach {
    // 真机联调测试（连真实网盘）要把过程打出来，否则看不到设备码 / errno
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
    // Robolectric 会把 android-all 下到 user.home/.m2 并在同级目录建下载锁；
    // 无桌面环境（容器 / CI）里 user.home 常常指向不可写目录，这里退回环境变量 HOME。
    System.getenv("HOME")?.let { home -> systemProperty("user.home", home) }
    // 有些环境的 /tmp 是几十 MB 的 tmpfs，Robolectric 解压 android-all、上传用例建临时文件都会炸，
    // 统一指到构建目录里，空间够也不会互相干扰。
    val testTmp = layout.buildDirectory.dir("test-tmp")
    doFirst { testTmp.get().asFile.mkdirs() }
    systemProperty("java.io.tmpdir", testTmp.get().asFile.absolutePath)
    // conscrypt 的 native 库不走解压，直接从磁盘加载
    dependsOn(extractTestNative)
    systemProperty(
        "java.library.path",
        listOf(testNativeDir.get().asFile.absolutePath, System.getProperty("java.library.path"))
            .joinToString(":"),
    )
}

/**
 * 出包：把 AGP 的产物摊到 `apk/` 下，按 README 的约定命名。
 *
 * 每种构建出两份：
 * - `apk/dinner-<版本>-debug.apk`：带版本号，多版本可以并存；
 * - `apk/dinner.apk`：固定名字，永远指向最新一次构建，方便直接发给人装。
 *
 * 写成 Gradle 任务而不是在 shell 里 cp，是因为构建目录被重定向到了工程外
 * （见上面 layout.buildDirectory），脚本里硬编码路径换台机器就失效。
 */
val apkOutputDir = rootProject.layout.projectDirectory.dir("apk")

/** 把某个 buildType 的产物复制成「版本化文件名」与「固定别名」两份 */
fun registerApkExport(typeName: String, assembleTask: String, sourceApk: String, alias: String) {
    tasks.register<Copy>("export${typeName.replaceFirstChar { it.uppercase() }}Apk") {
        group = "distribution"
        description = "复制 $typeName 包到 apk/ 并带上版本号"
        dependsOn(assembleTask)
        from(layout.buildDirectory.file("outputs/apk/$typeName/$sourceApk"))
        into(apkOutputDir)
        rename { "dinner-$appVersionName-$typeName.apk" }
    }
    tasks.register<Copy>("export${typeName.replaceFirstChar { it.uppercase() }}ApkAlias") {
        group = "distribution"
        description = "把最新一份 $typeName 包复制成 apk/$alias"
        dependsOn(assembleTask)
        from(layout.buildDirectory.file("outputs/apk/$typeName/$sourceApk"))
        into(apkOutputDir)
        rename { alias }
    }
}

registerApkExport("debug", "assembleDebug", "app-debug.apk", "dinner.apk")
registerApkExport("release", "assembleRelease", "app-release.apk", "dinner-release.apk")

/**
 * 再导出一份 `dinner <版本>.apk`（文件名带空格）。
 *
 * 这是给人直接发出去的那一份——用空格分隔版本号，手机上装完一眼能看出
 * 装的是哪个版本。Gradle 的 rename 里写空格没问题，只是 shell 里引用时要注意。
 */
tasks.register<Copy>("exportVersionedSpacedApk") {
    group = "distribution"
    description = "复制 release 包到 apk/ 并命名为 dinner <版本>.apk"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(apkOutputDir)
    rename { "dinner $appVersionName.apk" }
}

/**
 * 再导出一份 `dinner-<版本>.apk`（不带 debug / release 后缀）。
 *
 * 历史上一直有这个命名，是「正式包」的简称——发给人装时不需要知道
 * debug 还是 release，带后缀反而让人犹豫。
 */
tasks.register<Copy>("exportVersionedReleaseApk") {
    group = "distribution"
    description = "复制 release 包到 apk/ 并命名为 dinner-<版本>.apk"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(apkOutputDir)
    rename { "dinner-$appVersionName.apk" }
}

tasks.register("exportApks") {
    group = "distribution"
    description = "两种构建都出包（debug + release）"
    dependsOn(
        "exportDebugApk",
        "exportDebugApkAlias",
        "exportReleaseApk",
        "exportReleaseApkAlias",
        "exportVersionedSpacedApk",
        "exportVersionedReleaseApk",
    )
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // 指纹 / 人脸 / 锁屏密码的应用锁走官方 BiometricPrompt
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)

    // 状态挂在 ViewModel 上，转屏 / 进程重建都不丢；collectAsStateWithLifecycle 负责
    // 在后台时停止收集，避免同步中的 Flow 白白刷新界面
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    // 本地库：记录 / 媒体 / 评论 / 发件箱
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 网盘接口：几组 form / multipart 请求，OkHttp 足够，不引 Retrofit
    implementation(libs.okhttp)

    // 自动同步的后台调度：进程被杀、断网恢复都能接着传
    implementation(libs.androidx.work.runtime)

    debugImplementation(libs.androidx.compose.ui.tooling)
    // ---- 单元测试（JVM）----
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // Room 的 SQL 只有跑真实实现才测得出来：Robolectric 起一个内存库
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // android.jar 里的 org.json 是空壳，单元测试换成真实现
    testImplementation("org.json:json:20240303")
}
