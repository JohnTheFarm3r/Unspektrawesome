import com.android.build.api.variant.BuildConfigField
import java.security.MessageDigest
import java.security.KeyStore

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val telemetryProtoc by configurations.creating
val protocPlatform = when {
    System.getProperty("os.name").startsWith("Windows") -> "windows-x86_64"
    System.getProperty("os.name").startsWith("Mac") -> "osx-" + if (System.getProperty("os.arch") == "aarch64") "aarch_64" else "x86_64"
    else -> "linux-" + if (System.getProperty("os.arch") == "aarch64") "aarch_64" else "x86_64"
}
dependencies {
    telemetryProtoc("com.google.protobuf:protoc:3.25.5:$protocPlatform@exe")
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")
}

tasks.withType<Test>().configureEach {
    systemProperty("telemetry.fixture", providers.gradleProperty("telemetryFixture").getOrElse(""))
}
val telemetryJava = layout.buildDirectory.dir("generated/telemetry/java")
abstract class TelemetryProtoTask : Exec() {
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction override fun exec() {
        outputDirectory.get().asFile.mkdirs()
        super.exec()
    }
}
val generateTelemetry = tasks.register<TelemetryProtoTask>("generateTelemetry") {
    inputs.file("src/main/proto/gyroflow.proto")
    inputs.files(telemetryProtoc)
    outputDirectory.set(telemetryJava)
    commandLine(telemetryProtoc.singleFile.absolutePath, "--proto_path=${file("src/main/proto")}",
        "--java_out=lite:${telemetryJava.get().asFile.absolutePath}", "gyroflow.proto")
}

val vulkanValidationDir = providers.gradleProperty("vulkanValidationDir").orNull?.let(::file)
val qaNativeBuildType = providers.gradleProperty("qaNativeBuildType").orNull
check(qaNativeBuildType == null || qaNativeBuildType in listOf("Debug", "RelWithDebInfo"))
if (vulkanValidationDir != null) {
    check(vulkanValidationDir.resolve("jniLibs/arm64-v8a/libVkLayer_khronos_validation.so").isFile)
    check(vulkanValidationDir.resolve("assets/vulkan-validation/LICENSE.txt").isFile)
}

abstract class NativeIdentityTask : DefaultTask() {
    @get:Input abstract val hostPackage: Property<String>
    @get:Input abstract val debuggable: Property<Boolean>
    @get:Input abstract val qaFixtures: Property<Boolean>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val keyStoreFile: RegularFileProperty
    @get:Input abstract val storePassword: Property<String>
    @get:Input abstract val keyAlias: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun generate() {
        val keyStore = KeyStore.getInstance(keyStoreFile.get().asFile, storePassword.get().toCharArray())
        val certificate = requireNotNull(keyStore.getCertificate(keyAlias.get())) { "Signing certificate is missing" }
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        if (!debuggable.get()) {
            check(hostPackage.get() == "com.unspektrawesome")
            check(digest.joinToString("") { "%02x".format(it) } ==
                "8892d111163acf9c61c06b2b0e5a907b691d88f31d84c81e3d10dac0f1a6a79a") {
                "Production native libraries require the existing staging signing certificate"
            }
        }
        fun writeIdentity(directory: File, packageName: String, bytes: ByteArray) {
            directory.mkdirs()
            directory.resolve("NativeIdentity.h").writeText("""
                #pragma once
                namespace native_identity {
                inline constexpr char packageName[] = "$packageName";
                inline constexpr unsigned char certificate[] = {${bytes.joinToString(",") { "0x%02x".format(it) }}};
                inline constexpr bool debuggable = ${debuggable.get()};
                }
            """.trimIndent() + "\n")
        }
        val directory = outputDirectory.get().asFile
        writeIdentity(directory, hostPackage.get(), digest)
        if (qaFixtures.get()) {
            writeIdentity(directory.resolve("rejected_package"), "com.unspektrawesome.untrusted", digest)
            writeIdentity(directory.resolve("rejected_certificate"), hostPackage.get(), ByteArray(32))
        }
    }
}

android {
    namespace = "com.unspektrawesome"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.unspektrawesome"
        minSdk = 30
        targetSdk = 37
        versionCode = 32
        versionName = "1.3.1"
        buildConfigField("boolean", "UPDATES_ENABLED", "false")
        buildConfigField("boolean", "VULKAN_VALIDATION", "false")
        manifestPlaceholders["distributionChannel"] = "development"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-Wall", "-Wextra", "-Werror")
            }
        }
    }

    signingConfigs {
        create("staging") {
            // Passwords come from the user-level gradle.properties so they stay out of the source tree.
            storeFile = file("${System.getProperty("user.home")}/.android/unspektrawesome-staging.keystore")
            storePassword = providers.gradleProperty("unspektrawesomeStagingStorePassword").orNull
            keyAlias = providers.gradleProperty("unspektrawesomeStagingKeyAlias").orNull
            keyPassword = providers.gradleProperty("unspektrawesomeStagingKeyPassword").orNull
        }
    }

    buildTypes {
        create("qa") {
            initWith(getByName("debug"))
            externalNativeBuild.cmake.cppFlags += "-O2"
            qaNativeBuildType?.let { externalNativeBuild.cmake.arguments += "-DCMAKE_BUILD_TYPE=$it" }
            applicationIdSuffix = ".testing"
            versionNameSuffix = "-testing"
            matchingFallbacks += "debug"
        }
        create("staging") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("staging")
            buildConfigField("boolean", "UPDATES_ENABLED", "true")
            manifestPlaceholders["distributionChannel"] = "staging"
            matchingFallbacks += "debug"
            optimization {
                enable = true
            }
        }
        release {
            optimization {
                enable = true
            }
        }
    }
    testBuildType = "qa"
    for (variant in listOf("debug", "qa")) {
        buildTypes.getByName(variant) {
            buildConfigField("boolean", "VULKAN_VALIDATION", (vulkanValidationDir != null).toString())
            externalNativeBuild.cmake.arguments += "-DUNSPEKTRA_VULKAN_VALIDATION=${if (vulkanValidationDir != null) "ON" else "OFF"}"
        }
        if (vulkanValidationDir != null) {
            sourceSets.getByName(variant).jniLibs.srcDir(vulkanValidationDir.resolve("jniLibs"))
            sourceSets.getByName(variant).assets.srcDir(vulkanValidationDir.resolve("assets"))
        }
    }
    buildTypes.all {
        externalNativeBuild.cmake.arguments += listOf(
            "-DUNSPEKTRA_IDENTITY_DIR=${layout.buildDirectory.dir("generated/nativeIdentity/$name").get().asFile.invariantSeparatorsPath}",
            "-DUNSPEKTRA_HOST_TESTS=${if (name == "qa") "ON" else "OFF"}",
        )
    }
    sourceSets.getByName("test").kotlin.directories += "src/testShared/java"
    sourceSets.getByName("androidTest").kotlin.directories += "src/testShared/java"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

for (variant in listOf("debug", "qa", "staging", "release")) {
    val development = variant == "debug" || variant == "qa"
    val signing = android.signingConfigs.getByName(if (development) "debug" else "staging")
    val suffix = variant.replaceFirstChar { it.uppercase() }
    val identity = tasks.register<NativeIdentityTask>("generate${suffix}NativeIdentity") {
        hostPackage.set(if (variant == "qa") "com.unspektrawesome.testing" else "com.unspektrawesome")
        debuggable.set(development)
        qaFixtures.set(variant == "qa")
        keyStoreFile.set(requireNotNull(signing.storeFile))
        storePassword.set(signing.storePassword.orEmpty())
        keyAlias.set(signing.keyAlias.orEmpty())
        outputDirectory.set(layout.buildDirectory.dir("generated/nativeIdentity/$variant"))
        if (development) dependsOn("validateSigning$suffix")
    }
    tasks.configureEach {
        if (name == "externalNativeBuild$suffix") {
            dependsOn(identity)
        }
        if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) mustRunAfter(identity)
    }
    val verifyNative = tasks.register<Exec>("verify${suffix}NativeLibraries") {
        dependsOn("package$suffix", identity)
        commandLine("python", rootProject.file("tools/check_native_apk.py"),
            "--apk", layout.buildDirectory.file("outputs/apk/$variant/app-$variant${if (variant == "release") "-unsigned" else ""}.apk").get().asFile,
            "--identity", identity.get().outputDirectory.file("NativeIdentity.h").get().asFile,
            "--source", file("src/main/cpp"))
        if (variant == "qa") args("--qa")
    }
    tasks.configureEach {
        if (name == "assemble$suffix") dependsOn(verifyNative)
    }
}

tasks.named("preBuild").configure { dependsOn(generateTelemetry) }

val lensDiscoveryInputs = files(fileTree("src/main/java"), fileTree("src/main/cpp"),
    fileTree("src/main/keepRules"),
    "src/main/AndroidManifest.xml", "build.gradle.kts", rootProject.file("build.gradle.kts"),
    rootProject.file("gradle/libs.versions.toml"))
val languageAssets = layout.buildDirectory.dir("generated/languageAssets")
abstract class LanguageCatalogTask : Exec() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}
val generateLanguageCatalog = tasks.register<LanguageCatalogTask>("generateLanguageCatalog") {
    inputs.files(fileTree("src/main/java"), fileTree("src/main/cpp") { include("**/*.cpp") },
        fileTree("src/main/languages"), rootProject.file("tools/language_catalog.py"))
    outputDirectory.set(languageAssets)
    commandLine("python", rootProject.file("tools/language_catalog.py"), "--source", file("src/main/java"),
        "--native", file("src/main/cpp"),
        "--translations", file("src/main/languages"),
        "--output", languageAssets.get().file("languages/english.json").asFile)
}
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(generateTelemetry, TelemetryProtoTask::outputDirectory)
        variant.sources.assets?.addGeneratedSourceDirectory(generateLanguageCatalog, LanguageCatalogTask::outputDirectory)
        variant.buildConfigFields?.put("LENS_DISCOVERY_BUILD", providers.provider {
            val digest = MessageDigest.getInstance("SHA-256")
            lensDiscoveryInputs.files.filter { it.isFile }.sortedBy { it.path }.forEach {
                digest.update(it.relativeTo(rootDir).invariantSeparatorsPath.toByteArray())
                it.inputStream().use { stream ->
                    val buffer = ByteArray(8192)
                    var size = stream.read(buffer)
                    while (size >= 0) {
                        digest.update(buffer, 0, size)
                        size = stream.read(buffer)
                    }
                }
            }
            BuildConfigField("String", "\"${digest.digest().joinToString("") { "%02x".format(it) }}\"", null)
        })
    }
}

composeCompiler {
    includeSourceInformation = false
    generateFunctionKeyMetaClasses = false
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20250517")
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    add("qaImplementation", "androidx.compose.ui:ui-test-manifest")
}
