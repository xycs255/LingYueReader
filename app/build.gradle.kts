import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/*
 * 正式签名。密钥不进仓库，按顺序从这两处找：
 * - 环境变量 LINGYUE_KEYSTORE_FILE、LINGYUE_KEYSTORE_PASSWORD、LINGYUE_KEY_ALIAS、LINGYUE_KEY_PASSWORD（CI 用，
 *   见 .github/workflows/build.yml）；
 * - 项目根目录的 keystore.properties（本机用，已在 .gitignore 里），键为 storeFile、storePassword、keyAlias、keyPassword。
 * 都没有时 release 仍用调试签名，方便没有密钥的人（以及别人 fork 后的 PR）照样能构建、安装。
 */
val releaseKey: Map<String, String>? = run {
    val env = mapOf(
        "storeFile" to System.getenv("LINGYUE_KEYSTORE_FILE"),
        "storePassword" to System.getenv("LINGYUE_KEYSTORE_PASSWORD"),
        "keyAlias" to System.getenv("LINGYUE_KEY_ALIAS"),
        "keyPassword" to System.getenv("LINGYUE_KEY_PASSWORD"),
    )
    if (env.values.any { !it.isNullOrBlank() }) {
        val missing = env.filterValues { it.isNullOrBlank() }.keys
        require(missing.isEmpty()) { "正式签名环境变量不完整：${missing.joinToString()}" }
        return@run env.mapValues { it.value!! }
    }
    val file = rootProject.file("keystore.properties")
    if (!file.isFile) return@run null
    val props = Properties().apply { file.inputStream().use { load(it) } }
    val keys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    val missing = keys.filter { props.getProperty(it).isNullOrBlank() }
    require(missing.isEmpty()) { "keystore.properties 的正式签名配置不完整：${missing.joinToString()}" }
    keys.associateWith { props.getProperty(it) }
}

android {
    namespace = "com.xycs.lingyuereader"
    // miuix 0.9 要求 compileSdk 37。
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xycs.lingyuereader"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // 离线语音引擎带原生库：只打包手机用的两种（arm64 和老的 32 位 arm），省得安装包太大。
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        releaseKey?.let { key ->
            create("release") {
                storeFile = rootProject.file(key.getValue("storeFile"))
                storePassword = key.getValue("storePassword")
                keyAlias = key.getValue("keyAlias")
                keyPassword = key.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 有正式密钥用正式签名；没有时用调试签名，方便直接安装（两种签名的包不能互相覆盖安装）。
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            // 原生库压缩存放：安装包小一半，装好后系统再解压。
            useLegacyPackaging = true
            // sherpa-onnx 的 C / C++ 接口库，Kotlin 用不到（只用 JNI 那个）。
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
            // 本机模型的推理引擎只带 64 位的：32 位的手机跑不动 1.5B 的模型，带上只是白占 19 MB。
            excludes += listOf("lib/armeabi-v7a/libllm_inference_engine_jni.so")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)
    // 书源（OPDS、WebDAV）：WebDAV 要用 PROPFIND，HttpURLConnection 不支持。
    implementation(libs.okhttp)
    // 离线语音：sherpa-onnx 引擎（Apache-2.0），模型由读者按需下载；模型包是 .tar.bz2，用 commons-compress 解。
    implementation(variantOf(libs.sherpa.onnx) { artifactType("aar") })
    // 本机（离线）模型：MediaPipe 的 LLM 推理（Apache-2.0），模型由读者按需下载或导入。
    implementation(libs.mediapipe.genai)
    implementation(libs.commons.compress)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    // Robolectric 在 JDK 17+ 上需要访问这些内部包。
    jvmArgs(
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
    )
    maxHeapSize = "2g"
    // 测试的临时文件放进内存盘：Robolectric 每个用例的应用数据目录、TemporaryFolder 都在 java.io.tmpdir 下。
    // 写作流程一次要存几十个文件（每个都 fsync、改名，SharedPreferences 提交也 fsync），和别的构建共用一块盘时，
    // 别人大量写盘会让一次 fsync、改名卡上几百毫秒到几秒，界面测试就在 15 秒的等待上偶发超时（换谁不一定）。
    // 堆不是原因：全量跑下来 GC 后最多约 700M（上限 2g），GC 停顿只占 2% 以内。没有 /dev/shm 的机器照旧用系统临时目录。
    val ramDisk = File("/dev/shm")
    if (ramDisk.isDirectory && ramDisk.canWrite()) {
        // 每个 worktree、每个测试任务一个目录（同一个工程里测试任务不会同时跑）；开跑前清掉上一次没删干净的。
        val tmp = File(ramDisk, "lingyue-$name-${projectDir.absolutePath.hashCode().toUInt()}")
        systemProperty("java.io.tmpdir", tmp.absolutePath)
        doFirst {
            tmp.deleteRecursively()
            tmp.mkdirs()
        }
        doLast { tmp.deleteRecursively() }
    }
    // 测试里会建中文文件名（备份文件“灵阅备份-…”）；没设语言环境的机器上 JVM 会把文件名转成问号。
    environment("LC_ALL", "C.UTF-8")
    // 访问不了 Maven Central 时，可在 ~/.gradle/gradle.properties 里指定镜像。
    providers.gradleProperty("robolectricRepoUrl").orNull?.let {
        systemProperty("robolectric.dependency.repo.url", it)
    }
}
