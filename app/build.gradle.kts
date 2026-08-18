plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

val runtimeV2Enabled = providers.gradleProperty("runtimeV2Enabled").orNull
    ?.toBooleanStrictOrNull() ?: true
fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

fun externalSigningValue(propertyName: String, environmentName: String): String? = providers.gradleProperty(propertyName).orNull
    ?: providers.environmentVariable(environmentName).orNull

val releaseStorePath = externalSigningValue("zhiban.release.storeFile", "ZHIBAN_RELEASE_STORE_FILE")
val releaseStorePassword = externalSigningValue("zhiban.release.storePassword", "ZHIBAN_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = externalSigningValue("zhiban.release.keyAlias", "ZHIBAN_RELEASE_KEY_ALIAS")
val releaseKeyPassword = externalSigningValue("zhiban.release.keyPassword", "ZHIBAN_RELEASE_KEY_PASSWORD")
val releaseSigningValues = listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val releaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() }
check(releaseSigningValues.none { !it.isNullOrBlank() } || releaseSigningConfigured) {
    "Release signing configuration is incomplete; provide all four zhiban.release.* properties or ZHIBAN_RELEASE_* variables"
}

android {
    namespace = "com.zhiban.rebuild"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zhiban.rebuild"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "RUNTIME_V2_ENABLED", runtimeV2Enabled.toString())
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(releaseStorePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    lint {
        // 测试源码由编译 + 测试任务验证，不纳入静态扫描（与 ktlint 排除测试源、A0 质量基线一致）。
        // 也根除 lint 在测试源上的 FIR 解析崩溃（ContactFactDisplayNormalizerTest 等），那会让
        // ./gradlew :app:check 在模块结构改动后偶发失败、需 --rerun-tasks 才恢复。
        ignoreTestSources = true
    }
}

val verifyReleaseSigningConfiguration = tasks.register("verifyReleaseSigningConfiguration") {
    group = "verification"
    description = "Prevents producing an unsigned release artifact."
    doLast {
        check(releaseSigningConfigured) {
            "Release signing is required. Set zhiban.release.storeFile/storePassword/keyAlias/keyPassword or the matching ZHIBAN_RELEASE_* variables."
        }
        check(file(requireNotNull(releaseStorePath)).isFile) { "Release signing keystore does not exist" }
    }
}

tasks.matching { it.name == "packageRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyReleaseSigningConfiguration)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":agent:contracts"))
    implementation(project(":agent:provider"))
    implementation(project(":agent:context"))
    implementation(project(":agent:tools"))
    implementation(project(":agent:governance"))
    implementation(project(":agent:skills"))
    implementation(project(":agent:mcp"))
    implementation(project(":agent:memory"))
    implementation(project(":agent:runtime"))
    implementation(project(":agent:feature-ask"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.coil.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.sqlite)
    implementation("net.zetetic:sqlcipher-android:4.15.0@aar")
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mlkit.text.recognition.chinese)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
