import java.util.Properties
import org.gradle.api.tasks.PathSensitivity

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

// The desktop OpenCV: opencv-500.jar and x64/opencv_java500.dll from the
// official Windows release, found through opencv.dir in local.properties.
// There is no Maven artifact of it at the version the other two sides carry
// (see gradle/libs.versions.toml). core compiles against the jar only; on the
// phone the same classes come out of the Android artifact in app.
val opencvDir: File = run {
    val props = Properties()
    val local = rootProject.file("local.properties")
    if (local.exists()) local.inputStream().use { props.load(it) }
    val dir = props.getProperty("opencv.dir") ?: System.getenv("OPENCV_JAVA_DIR")
        ?: throw GradleException(
            "opencv.dir is not set: put the build/java folder of the OpenCV " +
                "5.0.0 Windows release into local.properties " +
                "(see README.md)")
    file(dir)
}
val opencvJar = opencvDir.resolve("opencv-500.jar")

// The project root: templates/, digits/, the oracle and the corpus.
val repoRoot: File = rootProject.projectDir

dependencies {
    compileOnly(files(opencvJar))
    testImplementation(files(opencvJar))
    testImplementation(kotlin("test"))
    testImplementation(libs.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// templates/ and digits/ on the test classpath, straight out of the
// repository and not copied: nothing reads them yet, the wiring is here so
// that P6 finds it.
//
// `include` filters the whole source set and not only the folder above it,
// so the module's own src/test/resources needs naming here too or it is
// dropped: that is where the activation test key lives.
sourceSets {
    test {
        resources {
            srcDir(repoRoot)
            include("templates/**", "digits/**", "activation/**")
        }
    }
}

// The tag on the eight suites that read a frame out of corpus/. It is
// OracleFamilies.CORPUS_TAG in the test sourceset, and this is the only
// other place that may name it: a build file cannot see a test constant,
// so the string stands twice and the doc comment there says so.
val corpusTag = "corpus"

// What every Test task of this module needs. The two system properties are
// how the readers find the native OpenCV and the checkout; everything the
// tests open -- templates/, digits/, oracle/, corpus/ -- is reached through
// the second one, and that is also why the inputs have to be declared by
// hand below.
fun Test.digiautotap(vararg withoutTags: String) {
    useJUnitPlatform { if (withoutTags.isNotEmpty()) excludeTags(*withoutTags) }
    systemProperty("java.library.path", opencvDir.resolve("x64").absolutePath)
    systemProperty("digiautotap.repo", repoRoot.absolutePath)
    maxHeapSize = "2g"
    testLogging {
        events("failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    // Gradle knows only the inputs it is told about, and it sees none of
    // these: they are opened through digiautotap.repo, an absolute path, long
    // after the up-to-date check is over. Without them the task reports
    // UP-TO-DATE in the two places it matters most -- right after
    // writeOracle has rewritten the contract, and right after a colour moved
    // in Theme.kt, which ThemeTest and CorpusTest are the guard for. Both
    // are small folders; hashing them costs nothing.
    inputs.dir(File(repoRoot, "oracle"))
        .withPropertyName("oracle")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(File(repoRoot, "app/src"))
        .withPropertyName("appSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // corpus/ is deliberately NOT an input: 1.1 GB, and it cannot change an
    // answer on its own. A frame promoted into it is followed by
    // writeOracle, which lands in oracle/ -- so the folder above already
    // carries every corpus change that can make a test say something else.
    //
    // templates/ and digits/ need no line here: they are test resources
    // (the sourceSets block above), so processTestResources puts them on the
    // classpath and the classpath is an input already.
}

// Everything, every suite -- the readers against the oracle over 773 frames
// and the rest. What NOTES.md means by "Before finishing any change", and
// the only one that proves anything about a reader. ~8.5 minutes.
tasks.test {
    digiautotap()
}

// Everything except the corpus. Measured 2026-09-21 over the whole suite:
// the eight tagged suites are 477 of 506 seconds, 15 tests of 330, so what
// is left runs in about half a minute -- and it still holds every flow
// test, the planner, the router, the settings, and the two that scan
// app/src (ThemeTest, CorpusTest).
//
// This is the one to run while working on app/, on a plan, or on anything
// that cannot reach a reader. It proves nothing about a reader: a change
// under core/src/main, or in oracle/, templates/ or digits/, goes through
// :core:test before it is committed.
tasks.register<Test>("fastTest") {
    description = "Every suite but the eight that read the corpus (~30 s)"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    digiautotap(corpusTag)
}

// The oracle, written from the readers as they are: every reader of every
// family over every frame of corpus/, into oracle/<family>.json. The families
// and the loop are the test sourceset's OracleFamilies.kt, the same table the
// *OracleTest suites hold the readers to (PLAN_STANDALONE.md 1).
//
//   gradlew :core:writeOracle                    every family
//   gradlew :core:writeOracle --args=dungeon     one
//
// Then `git diff oracle/` is the list of frames that now read differently.
tasks.register<JavaExec>("writeOracle") {
    description = "Writes oracle/<family>.json from corpus/ with the readers as they are"
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.digipr1me.digiautotap.core.OracleWriterKt")
    systemProperty("java.library.path", opencvDir.resolve("x64").absolutePath)
    systemProperty("digiautotap.repo", repoRoot.absolutePath)
    maxHeapSize = "2g"
}

// What a rectangle over the game costs: the overlay's plate in several
// sizes, masked into every frame of the corpus and every reader asked
// again. Prints, writes nothing (OverlayPlateProbe.kt).
tasks.register<JavaExec>("overlayProbe") {
    description = "Measures what the overlay's plate costs over the corpus, in several sizes"
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.digipr1me.digiautotap.core.OverlayPlateProbeKt")
    systemProperty("java.library.path", opencvDir.resolve("x64").absolutePath)
    systemProperty("digiautotap.repo", repoRoot.absolutePath)
    maxHeapSize = "3g"
}
