import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
}

// The project root: templates/ and digits/ live there, once, for the
// core tests and this app alike.
val repoRoot: File = rootProject.projectDir

// templates/ and digits/ as assets, out of the repository and not kept as a
// second copy anywhere under app/: a sync into the build folder, so the APK
// sees assets/templates/... and assets/digits/....
//
// codes.txt used to be the third, and went on 2026-09-22: the supporter pool
// is the activation server's table now and the APK carries no list of codes
// at all, while the ledger beside it (codes_issued.csv) never was named
// here. ShellCoreTest holds this file to exactly these two.
abstract class SharedAssets : DefaultTask() {
    @get:InputDirectory abstract val templates: DirectoryProperty
    @get:InputDirectory abstract val digits: DirectoryProperty
    @get:OutputDirectory abstract val output: DirectoryProperty
    @get:Inject abstract val fs: FileSystemOperations

    @TaskAction
    fun sync() {
        fs.sync {
            into(output)
            into("templates") { from(templates) }
            into("digits") { from(digits) }
        }
    }
}

val sharedAssets = tasks.register<SharedAssets>("sharedAssets") {
    templates.set(repoRoot.resolve("templates"))
    digits.set(repoRoot.resolve("digits"))
}

android {
    namespace = "io.github.digipr1me.digiautotap"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.digipr1me.digiautotap"
        // Android 11: takeScreenshot from an accessibility service, with no
        // consent dialog.
        minSdk = 30
        targetSdk = 36
        // It must only ever go up, or Android refuses the update (NOTES.md,
        // "Publishing"). 3 is 1.0, the first release published, and the one
        // that carries the device binding PLAN_SUPPORTER_SERVER.md is written
        // around; 2 (0.2) was built and never released.
        versionCode = 5
        versionName = "1.2"
    }

    // Two flavors, one ABI each. OpenCV is nearly the whole APK: 108.1 MB
    // with both ABIs, 38.6 MB arm64 alone, 73.3 MB x86_64 alone
    // (PLAN_ANDROID_1_SPIKE.md 5). What a player installs is the phone, and
    // there is no reason to ship 70 MB for an ABI no phone has; LDPlayer 14
    // is x86_64 and gets a debug build of its own. LDPlayer also runs the
    // phone build, through its ARM translation, only three to eight times
    // slower (same section).
    flavorDimensions += "target"
    productFlavors {
        create("phone") {
            dimension = "target"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("emulator") {
            dimension = "target"
            ndk { abiFilters += "x86_64" }
        }
    }

    // The release is signed with a key that is not in this repository and
    // whose place is not written here either: keystore.properties
    // (gitignored) names it, or the environment does, a variable winning
    // over the file value by value. See README.md.
    //
    //   storeFile      DIGIAUTOTAP_KEYSTORE            an absolute path
    //   storePassword  DIGIAUTOTAP_KEYSTORE_PASSWORD
    //   keyAlias       DIGIAUTOTAP_KEY_ALIAS
    //   keyPassword    DIGIAUTOTAP_KEY_PASSWORD        defaults to storePassword
    val keystoreFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().also { props ->
        if (keystoreFile.exists()) keystoreFile.inputStream().use { props.load(it) }
    }
    fun signingValue(key: String, env: String): String? =
        System.getenv(env)?.takeIf { it.isNotBlank() }
            ?: keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() }

    val signStore = signingValue("storeFile", "DIGIAUTOTAP_KEYSTORE")
    val signStorePassword = signingValue("storePassword", "DIGIAUTOTAP_KEYSTORE_PASSWORD")
    val signAlias = signingValue("keyAlias", "DIGIAUTOTAP_KEY_ALIAS")
    val signKeyPassword = signingValue("keyPassword", "DIGIAUTOTAP_KEY_PASSWORD")
        ?: signStorePassword
    val signingMissing = listOfNotNull(
        "storeFile / DIGIAUTOTAP_KEYSTORE".takeIf { signStore == null },
        "storePassword / DIGIAUTOTAP_KEYSTORE_PASSWORD".takeIf { signStorePassword == null },
        "keyAlias / DIGIAUTOTAP_KEY_ALIAS".takeIf { signAlias == null },
    )

    signingConfigs {
        create("release") {
            if (signingMissing.isEmpty()) {
                storeFile = file(signStore!!)
                storePassword = signStorePassword
                keyAlias = signAlias
                keyPassword = signKeyPassword
                // v3 is what lets the key be rotated one day. The key is
                // meant to last as long as the app does, and an APK signed
                // without v3 cannot be moved to a new one. With minSdk 30
                // the signer writes v3 alone -- measured with apksigner: v2
                // false, v3 true -- and every device the app runs on checks
                // that.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // A release with no key is not built unsigned: an APK nobody can
    // install over the last one is worse than a build that says why it did
    // not happen. Checked against the tasks that were asked for, so the
    // debug builds and the core tests need no key at all.
    gradle.taskGraph.whenReady {
        val release = allTasks.any {
            it.project == project && it.name.contains("Release", ignoreCase = true)
        }
        if (!release) return@whenReady
        if (signingMissing.isNotEmpty()) {
            throw GradleException(
                "No signing key for the release build. Missing: " +
                    signingMissing.joinToString(", ") + ". Put them in " +
                    "keystore.properties or the environment, see " +
                    "README.md.")
        }
        val store = file(signStore!!)
        if (!store.isFile) {
            throw GradleException("The keystore named for the release build " +
                "does not exist: $store")
        }
        // The key belongs outside the repository, and a path that leads back
        // in is how one ends up committed.
        if (store.canonicalFile.toPath().startsWith(repoRoot.canonicalFile.toPath())) {
            throw GradleException("The keystore is inside the repository " +
                "($store). Keep it outside, so it cannot be committed or " +
                "shipped.")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

androidComponents {
    // There is no release for LDPlayer: it would be a signed 73 MB APK
    // nobody is meant to hand out.
    beforeVariants { variant ->
        if (variant.buildType == "release" &&
            variant.productFlavors.contains("target" to "emulator")) {
            variant.enable = false
        }
    }
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(sharedAssets, SharedAssets::output)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.opencv.android)
}
