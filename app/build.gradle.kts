// app 模块构建配置（计划 §6.4）
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.photon.remote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.photon.remote"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // release 签名配置：兼容本地与 CI。
    // 候选路径优先级：D:/Android/keystore/keystore.properties（本地 Windows 绝对路径，历史兼容）
    // → $rootDir/keystore.properties → $projectDir/keystore.properties → /tmp/keystore.properties
    // CI 通过 secrets 注入时会在上述任一路径生成 keystore.properties。
    // 绝不进入 git（已被 .gitignore 屏蔽）。
    signingConfigs {
        create("release") {
            val candidateFiles = listOf(
                file("D:/Android/keystore/keystore.properties"),
                file("$rootDir/keystore.properties"),
                file("$projectDir/keystore.properties"),
                file("/tmp/keystore.properties"),
                file("/tmp/keystore/keystore.properties"),
            )
            val propsFile = candidateFiles.firstOrNull { it.exists() }
            if (propsFile != null && propsFile.exists()) {
                val props = Properties().apply { propsFile.inputStream().use { load(it) } }
                if (props.isNotEmpty()) {
                    // storeFile 可能是绝对路径或相对路径；相对路径以 propsFile 所在目录为基准
                    val rawStore = props.getProperty("storeFile")?.trim().orEmpty()
                    storeFile = if (rawStore.isNotEmpty()) {
                        val f = file(rawStore)
                        if (f.isAbsolute) f else File(propsFile.parentFile, rawStore)
                    } else null
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                }
                // 显式启用 v2/v3 签名：v1（JAR 签名）在 minSdk>=24 时被 AGP 8.x 忽略
                // （minSdk 24 起所有设备均支持 v2，v1 属遗留弱方案，但为兼容旧设备仍开启）
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // keystore 就绪 → release 签名；否则回退 debug 签名并告警（仅供本地调试，禁止分发）
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile?.exists() == true) {
                signingConfig = releaseSigning
            } else {
                logger.warn("release keystore 缺失（${releaseSigning.storeFile}），release 包将使用 debug 签名，不可用于正式分发！")
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    testOptions {
        unitTests {
            // Robolectric 需要打包 Android 资源（Room in-memory 单测用）
            isIncludeAndroidResources = true
        }
    }
    // APK 产物文件名：PhotonRemote-<buildType>-v<versionName>.apk（release/debug 都改）
    // AGP 8.9 的 androidComponents.VariantOutput 公开 API 已移除 outputFileName（仅剩 versionName/versionCode），
    // 官方 Gradle Recipe 的替代做法是监听 SingleArtifact.APK 复制改名。此处用 AGP 8.x 仍支持的
    // applicationVariants（弃用但可用，AGP 9 才移除）直接改写产物文件名，产物仍落在标准目录。
    applicationVariants.configureEach {
        val variant = this
        outputs.configureEach {
            val apkOutput = this as com.android.build.gradle.api.ApkVariantOutput
            val apkName = "PhotonRemote-${variant.buildType.name}-v${variant.mergedFlavor.versionName}.apk"
            apkOutput.outputFileName = apkName
        }
    }
}

// Room schema 导出目录（KSP 生成 database schema JSON，验收项：schema 目录生成）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.windowSizeClass)  // calculateWindowSizeClass(activity) 所需
    implementation(libs.androidx.material3.adaptive.navigation.suite)  // NavigationSuiteScaffold（material3 1.3.x 迁移至此）
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.adaptive)
    implementation(libs.androidx.adaptive.layout)
    implementation(libs.androidx.adaptive.navigation)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    // Room in-memory 单测（Robolectric 提供 Android 框架环境）
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.7.0")
}
