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

// The tag on the suites that read a frame out of corpus/. It is
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
    // The classes side by side, in up to four JVMs. Until 2026-10-03 a run
    // was one JVM and one class after the other, and had grown to 60 minutes
    // (1886 frames, 20 logical processors); with the oracle suites asking
    // on eight threads each (OracleWriter.collect) it is 14. Gradle deals
    // the classes out in turn, by name and not by weight, so a class cannot
    // count on the one before it: each loads the native itself, and every
    // class of fastTest passed alone in a JVM of its own that day
    // (forkEvery = 1).
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 4).coerceIn(1, 4)
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
    // corpus/ is deliberately NOT an input: 2.4 GB, and it cannot change an
    // answer on its own. A frame promoted into it is followed by
    // writeOracle, which lands in oracle/ -- so the folder above already
    // carries every corpus change that can make a test say something else.
    //
    // templates/ and digits/ need no line here: they are test resources
    // (the sourceSets block above), so processTestResources puts them on the
    // classpath and the classpath is an input already.
}

// Everything, every suite -- the readers against the oracle over 1886 frames
// and the rest. What NOTES.md means by "Before finishing any change", and
// the only one that proves anything about a reader. 14 minutes.
tasks.test {
    digiautotap()
}

// Everything except the corpus. Measured 2026-10-03 over the whole suite:
// the tagged suites are 76 tests of 923 and eleven of the fourteen minutes,
// so what is left runs in 2 min 44 s -- and it still holds every flow
// test, the planner, the router, the settings, and the two that scan
// app/src (ThemeTest, CorpusTest).
//
// This is the one to run while working on app/, on a plan, or on anything
// that cannot reach a reader. It proves nothing about a reader: a change
// under core/src/main, or in oracle/, templates/ or digits/, goes through
// :core:test before it is committed.
tasks.register<Test>("fastTest") {
    description = "Every suite but the ones that read the corpus (~3 min)"
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

// What the readers say about pictures that are not in corpus/ yet: named
// families on named pictures, every answer printed (ReaderProbe.kt). How a
// frame is measured before it is moved into the corpus by hand.
//
//   gradlew :core:readerProbe --args="explore,director staging/farm"
tasks.register<JavaExec>("readerProbe") {
    description = "Prints what the readers of the named families say about the named pictures"
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.digipr1me.digiautotap.core.ReaderProbeKt")
    systemProperty("java.library.path", opencvDir.resolve("x64").absolutePath)
    systemProperty("digiautotap.repo", repoRoot.absolutePath)
    maxHeapSize = "2g"
}

// The numbers behind the Meat Field's small counts (PLAN_ABSCHLUSS_1_3.md
// K10, SeedDigitProbe.kt): every glyph under the seed menu's slots and in
// the header, as Dungeon.readDigit measures it, as is and at the 1920
// scale. Prints, writes nothing.
//
//   gradlew :core:seedDigitProbe --no-daemon --args="corpus/farm staging/farm900"
tasks.register<JavaExec>("seedDigitProbe") {
    description = "Prints what Dungeon.readDigit measures on the Meat Field's small counts"
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.digipr1me.digiautotap.core.SeedDigitProbeKt")
    systemProperty("java.library.path", opencvDir.resolve("x64").absolutePath)
    systemProperty("digiautotap.repo", repoRoot.absolutePath)
    maxHeapSize = "2g"
}

// The numbers behind the EX Missions readers (PLAN_EX_MISSIONS.md 4.1,
// MissionsProbe.kt): the Missions tile over the main screens, the window's
// tab row and Claims over any pictures. Prints, writes nothing.
//
//   gradlew :core:missionsProbe --no-daemon --args="tile main staging/formats"
tasks.register<JavaExec>("missionsProbe") {
    description = "Prints the numbers behind the Missions readers over the named pictures"
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.digipr1me.digiautotap.core.MissionsProbeKt")
    systemProperty("java.library.path", opencvDir.resolve("x64").absolutePath)
    systemProperty("digiautotap.repo", repoRoot.absolutePath)
    maxHeapSize = "2g"
}

// The numbers behind what a dungeon run shows between two screens the
// readers know (PLAN_RELEASE_1_3.md B6 and B11, RunProbe.kt): the Reward
// sheet's band, a black canvas, the VS screen, over the corpus or any
// pictures. Prints, writes nothing.
//
//   gradlew :core:runProbe --no-daemon --args="dark corpus staging/b6"
tasks.register<JavaExec>("runProbe") {
    description = "Prints the numbers behind the sheet, the black frame and the VS screen over the named pictures"
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.digipr1me.digiautotap.core.RunProbeKt")
    systemProperty("java.library.path", opencvDir.resolve("x64").absolutePath)
    systemProperty("digiautotap.repo", repoRoot.absolutePath)
    maxHeapSize = "2g"
}

// The numbers behind the title screen's readers (PLAN_RELEASE_1_3.md B10,
// TitleProbe.kt): the auto button's band and the Touch To Start bar, over
// the corpus and named pictures. Prints, writes nothing.
//
//   gradlew :core:titleProbe --no-daemon --args="auto corpus staging/r2"
tasks.register<JavaExec>("titleProbe") {
    description = "Prints the numbers behind the auto button's band and the title bar"
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.digipr1me.digiautotap.core.TitleProbeKt")
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

// Does a display format read like 1080 x 1920: every frame of corpus/tall and
// corpus/formats beside its twin on 1080 x 1920, every reader asked on both,
// and every answer that differs printed (PLAN_FORMATE.md 6, FormatProbe.kt).
// `--args="pair ref.png here.png@inset"` takes any two pictures instead.
// Prints, and keeps the whole report in build/formatProbe.txt; writes nothing
// else.
tasks.register<JavaExec>("formatProbe") {
    description = "Reads every format frame against its 1080 x 1920 twin, reader by reader"
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.digipr1me.digiautotap.core.FormatProbeKt")
    systemProperty("java.library.path", opencvDir.resolve("x64").absolutePath)
    systemProperty("digiautotap.repo", repoRoot.absolutePath)
    maxHeapSize = "3g"
}

// The Digital World Search board on every board frame of the corpus
// (PLAN_WORLD_SEARCH_FORMATE.md 4.1, BoardProbe.kt): calibration, figure,
// grid with every cell's score, the seven counters, the X, the screen, and
// the time per reader; `--args=twin` lays each frame beside its 1080 x 1920
// twin of the same scene; `--args=objects` weighs the bottom row's objects
// (F10, BoardObjects.kt) and keeps crops in build/boardProbe_cells/;
// `--args=meters` says where the counters' digits touch (F25, MeterGlyphs.kt),
// `extra=<png>` reads a frame outside the corpus.
// Prints, and keeps the report in build/boardProbe.txt; writes nothing else.
tasks.register<JavaExec>("boardProbe") {
    description = "Reads every board frame of the corpus, cell by cell, with the time per reader"
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.digipr1me.digiautotap.core.BoardProbeKt")
    systemProperty("java.library.path", opencvDir.resolve("x64").absolutePath)
    systemProperty("digiautotap.repo", repoRoot.absolutePath)
    maxHeapSize = "2g"
}

// Objects the board covers from below (PLAN_WORLD_SEARCH_FORMATE.md F12, F16,
// F17, CoverProbe.kt): every cell of every board frame asked more than
// readGrid asks -- the pyramid cut to its upper rows, the arrow, the
// power-ups' upper halves -- beside what stands under and over it; the table
// in build/coverProbe.tsv, crops with `crops`, frames outside the corpus with
// `extra=<dir or png>`. Writes nothing else.
tasks.register<JavaExec>("coverProbe") {
    description = "Every board cell against the pyramid's upper rows, the arrow and the power-ups' upper halves"
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.digipr1me.digiautotap.core.CoverProbeKt")
    systemProperty("java.library.path", opencvDir.resolve("x64").absolutePath)
    systemProperty("digiautotap.repo", repoRoot.absolutePath)
    maxHeapSize = "2g"
}

// The fifteen partners on the World Search board (PLAN_WORLD_SEARCH_FORMATE.md
// 13, B7; SkinTour.kt): drives one LDPlayer instance over adb from the JVM --
// the bond tour's own enter/raise and WorldSearchSkill.run -- and keeps the
// board's start frame of every pass in the folder named by `dir=`.
//   gradlew :core:skinTour --args="list"      and raise N, pass N MAX, skin N MAX, home
val sdkDir: File? = run {
    val props = Properties()
    val local = rootProject.file("local.properties")
    if (local.exists()) local.inputStream().use { props.load(it) }
    props.getProperty("sdk.dir")?.let { file(it) }
}
tasks.register<JavaExec>("skinTour") {
    description = "Drives the partners over the World Search board on an LDPlayer instance, over adb"
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.digipr1me.digiautotap.core.SkinTourKt")
    systemProperty("java.library.path", opencvDir.resolve("x64").absolutePath)
    systemProperty("digiautotap.repo", repoRoot.absolutePath)
    sdkDir?.let { systemProperty("digiautotap.adb", it.resolve("platform-tools/adb.exe").absolutePath) }
    maxHeapSize = "2g"
}

// The display self-check's landmarks over the whole corpus (PLAN_FORMATE.md
// 10, SelfCheckProbe.kt): every globe and experience bar found without
// assuming a place, beside where game_rect expects them, and the time per
// check. Prints, and keeps the report in build/selfCheckProbe.txt.
tasks.register<JavaExec>("selfCheckProbe") {
    description = "The display self-check's landmarks and verdicts over the corpus"
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.digipr1me.digiautotap.core.SelfCheckProbeKt")
    systemProperty("java.library.path", opencvDir.resolve("x64").absolutePath)
    systemProperty("digiautotap.repo", repoRoot.absolutePath)
    maxHeapSize = "2g"
}

// The planner's first decision on every position the corpus's boards give
// it (PLAN_WORLD_SEARCH_FORMATE.md 4.2, PlannerProbe.kt): the figure on every
// free cell of its two columns, claws 3 and 0, dashEager off and on, an
// exception a line of the report. Keeps the report in build/plannerProbe.txt
// and one decision per position in build/plannerProbe.tsv, which
// `--args="compare=<file>"` holds a later run against.
tasks.register<JavaExec>("plannerProbe") {
    description = "The planner's first decision on every board position of the corpus"
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.digipr1me.digiautotap.core.PlannerProbeKt")
    systemProperty("java.library.path", opencvDir.resolve("x64").absolutePath)
    systemProperty("digiautotap.repo", repoRoot.absolutePath)
    maxHeapSize = "2g"
}
