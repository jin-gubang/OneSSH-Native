import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
}

/* ==========================================================================
 * 版本号：唯一来源是仓库根目录的 version.properties
 *
 * 每次真正构建 APK / AAB（任务名含 assemble / bundle / package）时，
 * 在配置阶段把 patch 与 build 各 +1 并写回文件，因此：
 *   - 一次编译 = 一个新版本号，不需要手动改任何数字；
 *   - 产物名、Release tag、应用内「关于」三处天然一致；
 *   - versionCode 由 major.minor.patch 推算，天然单调递增，可覆盖安装。
 *
 * 锁版本构建（重复调试、CI 的 PR 构建）：-PnoBump 或 ONESSH_NO_BUMP=1
 * ========================================================================== */

val versionFile: File = rootProject.file("version.properties")

/* 固定的 debug 签名：仓库里有一份调试 keystore（app/debug.keystore.base64）。
 * CI 在构建前把它解码到 app/debug.keystore；只要该文件存在，debug 包就用它签名，
 * 指纹固定，可覆盖安装。本地没有该文件时沿用 AGP 默认的 $HOME/.android/debug.keystore。
 * 注意：只靠工作流往 $HOME/.android 写文件是靠不住的——实测两版 CI 包用了不同的
 * 现生成 key（指纹 61fcdfa5… / 63aacd4c…，与仓库 key 的 7d795239… 都不同），
 * 所以这里显式指定签名配置，不依赖默认路径。*/
val fixedDebugKeystore: File = rootProject.file("app/debug.keystore")

val versionProps = Properties().apply {
    if (versionFile.exists()) versionFile.inputStream().use { load(it) }
}

fun vProp(key: String, fallback: Int): Int =
    versionProps.getProperty(key)?.trim()?.toIntOrNull() ?: fallback

/** 只有真要出包时才递增；`gradlew tasks`、`sync`、`clean` 都不会动它 */
val packagingRequested = gradle.startParameter.taskNames.any { name ->
    val n = name.lowercase()
    n.contains("assemble") || n.contains("bundle") || n.contains("package")
}

val autoBumpOn = versionProps.getProperty("autoBump", "true").trim().toBoolean()

val bumpDisabled = project.hasProperty("noBump")
        || gradle.startParameter.isDryRun
        || System.getenv("ONESSH_NO_BUMP") == "1"
        || !autoBumpOn

var vMajor = vProp("major", 2)
var vMinor = vProp("minor", 0)
var vPatch = vProp("patch", 0)
var vBuild = vProp("build", 0)

if (packagingRequested && !bumpDisabled) {
    vPatch += 1
    vBuild += 1
    versionFile.writeText(
        """
        # OneSSH 版本号（唯一来源，由构建脚本自动维护，一般不要手改）
        #
        # 规则：
        #   每次执行 assemble* / bundle* 时，patch 与 build 各 +1，并立刻写回本文件，
        #   所以一次编译就会得到一个全新的版本号，产物名、Release tag、应用内「关于」
        #   全都跟着变。
        #
        #   想锁住版本号重复构建（打补丁、临时调试）：
        #     ./gradlew assembleDebug -PnoBump
        #     或设置环境变量 ONESSH_NO_BUMP=1
        #     或把下面 autoBump 改成 false（改完记得改回来）
        #
        #   想手动定版本：直接改 major / minor / patch，下一次构建从新值继续递增。
        #   versionName = major.minor.patch ；versionCode = major*1000000 + minor*10000 + patch
        major=$vMajor
        minor=$vMinor
        patch=$vPatch
        build=$vBuild
        autoBump=$autoBumpOn
        """.trimIndent() + "\n"
    )
    logger.lifecycle("OneSSH 版本号已递进 → $vMajor.$vMinor.$vPatch（第 $vBuild 次构建）")
}

val appVersionName: String =
    (project.findProperty("versionName") as String?) ?: "$vMajor.$vMinor.$vPatch"
val appVersionCode: Int =
    (project.findProperty("versionCode") as String?)?.toIntOrNull()
        ?: (vMajor * 1_000_000 + vMinor * 10_000 + vPatch)

android {
    namespace = "com.onessh.app"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.onessh.app"
        minSdk = 26
        targetSdk = 34
        // 两个值都由上面的 version.properties 推算出来，这里不再写死数字
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // pty 桥（Mosh 子进程用）：src/main/cpp/CMakeLists.txt，编出 libptybridge.so。
    // 打包进 APK 的 mosh-client 二进制在 src/main/jniLibs/<abi>/，不用编。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        getByName("debug") {
            if (fixedDebugKeystore.exists()) {
                storeFile = fixedDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // 离线环境里跑不了 lintVital（它要单独下一个 lint-gradle 包），关掉
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

dependencies {
    // 纯 Java 的 SSH2 实现：密码 / 公钥 / keyboard-interactive、PTY shell、SFTP 全都有，
    // 没有传递依赖，APK 只多 500 KB 左右。
    // 直接引用 libs 里的 jar，构建时不需要联网。
    implementation(files("libs/jsch-2.28.8.jar"))
}

/** `./gradlew versionInfo`：只看版本号，不构建、不递增 */
tasks.register("versionInfo") {
    group = "help"
    description = "打印当前版本号（不触发递增）"
    val name = appVersionName
    val code = appVersionCode.toString()
    val buildNo = vBuild.toString()
    doLast {
        println("OneSSH $name  ·  versionCode $code  ·  第 $buildNo 次构建")
    }
}
