import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files

plugins {
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("eddies.mapeditor.EddiesMapEditor")
}

// TripleA is not shipped with the editor: it runs with the player's installed TripleA, whose one jar
// (bin/game-headed-*.jar) also brings FlatLaf, logback and the rest. The build uses an installed
// TripleA too, or an unzipped game-headed.zip of a TripleA release: -PtripleaHome=<folder>.
val tripleaHome = providers.gradleProperty("tripleaHome")
    .orElse(providers.environmentVariable("ProgramFiles").map { "$it/TripleA" })
    .getOrElse("/Applications/TripleA.app/Contents/app")
val triplea = fileTree(tripleaHome) { include("bin/game-headed-*.jar") }

dependencies {
    compileOnly(triplea)
    testImplementation(triplea)
    // the text editors: coloring for XML, HTML and properties, line numbers, find, themes
    implementation("com.fifesoft:rsyntaxtextarea:3.6.0")
    compileOnly("org.projectlombok:lombok:1.18.48")
    annotationProcessor("org.projectlombok:lombok:1.18.48")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testCompileOnly("org.projectlombok:lombok:1.18.48")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.48")
}

tasks.test {
    useJUnitPlatform()
    // Java preferences in memory: no test reads or writes what the developer chose here
    systemProperty("java.util.prefs.PreferencesFactory", "eddies.mapeditor.MemoryPreferencesFactory")
    // the user guide's screenshots: -Dguide.screenshots=true runs GuideScreenshots, which writes
    // docs/guide/img from the editor as it runs; a map's folder to show with -Dguide.map=<folder>
    systemProperty("guide.screenshots", System.getProperty("guide.screenshots") ?: "")
    systemProperty("guide.map", System.getProperty("guide.map") ?: "")
    systemProperty("guide.dark", System.getProperty("guide.dark") ?: "true")
    // the editor's Backups folder for the tests, emptied first: the developer's own stays as it is
    val testBackups = layout.buildDirectory.dir("test-backups").get().asFile
    systemProperty("eddies.backups", testBackups.path)
    doFirst { testBackups.deleteRecursively() }
}

// the repo's git hooks: pre-commit runs UserGuideTest when the sources or the guide change, so the
// user guide moves with the editor; set once per clone, on the first build
val hasGitAndHooks = file(".git").exists() && file(".githooks").isDirectory
val installGitHooks by tasks.registering {
    description = "Points git at .githooks, whose pre-commit checks the user guide"
    val root = projectDir
    val wanted = hasGitAndHooks
    onlyIf { wanted }
    doLast {
        try {
            ProcessBuilder("git", "config", "core.hooksPath", ".githooks").directory(root).start().waitFor()
        } catch (e: java.io.IOException) {
            // no git on the PATH: the hooks are for those who commit
        }
    }
}
tasks.named("compileJava") { dependsOn(installGitHooks) }

tasks.named<JavaExec>("run") {
    classpath += triplea
}

if (triplea.isEmpty) {
    logger.warn("No TripleA in $tripleaHome: install TripleA, or -PtripleaHome=<folder with bin/game-headed-*.jar>")
}

// The editor as a program with its own Java, for the system the build runs on: on Windows a folder
// with "Eddies Map Editor.exe". jpackage comes from the Java 25 toolchain.
val appName = "Eddies Map Editor"
// jpackage takes numbers only, eg 1.2.0: -PappVersion=1.2.0
val appVersion = providers.gradleProperty("appVersion").getOrElse("1.0.0")

// The editor shows its version (File menu, start page) from its jar's manifest
// (EddiesMapEditor.version); a build without -PappVersion has none and shows "dev".
tasks.jar {
    providers.gradleProperty("appVersion").orNull?.let { version ->
        manifest { attributes("Implementation-Version" to version) }
    }
}
val jpackage = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    .map { it.metadata.installationPath.file("bin/jpackage").asFile.absolutePath }
val appImageDir = layout.buildDirectory.dir("app-image")
val isWindows = System.getProperty("os.name").startsWith("Windows")
val isMac = System.getProperty("os.name").startsWith("Mac")
// macOS takes no version starting with 0: there 0.1.6 is packaged as 1.1.6; the editor itself
// shows the jar's version, the real one
val packagedVersion = if (isMac && appVersion.startsWith("0.")) "1" + appVersion.drop(1) else appVersion

val appImage = tasks.register<Exec>("appImage") {
    group = "distribution"
    description = "Builds the editor as a program with its own Java in build/app-image"
    dependsOn(tasks.named("installDist"))
    val lib = layout.buildDirectory.dir("install/eddies-map-editor/lib")
    inputs.dir(lib)
    inputs.property("appVersion", appVersion)
    outputs.dir(appImageDir)
    val outputFolder = appImageDir.get().asFile
    // jpackage refuses to write over an earlier image; its exe is read-only, which on Windows
    // keeps it from being deleted
    doFirst {
        outputFolder.walkBottomUp().forEach { it.setWritable(true) }
        outputFolder.deleteRecursively()
    }
    executable(jpackage.get())
    args(
        "--type", "app-image",
        "--name", appName,
        "--app-version", packagedVersion,
        "--vendor", "Eddie",
        "--input", lib.get().asFile.absolutePath,
        "--main-jar", "eddies-map-editor.jar",
        "--main-class", "eddies.mapeditor.EddiesMapEditor",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--jlink-options", "--strip-debug --no-header-files --no-man-pages",
        "--dest", outputFolder.absolutePath,
    )
    if (isWindows) {
        args("--icon", file("packaging/icon.ico").absolutePath)
    }
}

// The Windows installer of that program (#28): an msi for the current user, so it asks for no
// administrator and a later update needs none either, with a Start menu entry and an uninstaller.
// jpackage needs the WiX toolset for it (candle and light on the PATH, or installed where WiX
// installs itself). The upgrade code never changes: by it a newer installer replaces the
// installed version in place.
// Its pages carry the editor's own pictures, packaging/installer (drawn by make-bitmaps.py): for
// them jpackage's main.wxs, read from the JDK that packages, gets two lines naming the pictures.
val installerDir = layout.buildDirectory.dir("installer")
val installerName = "eddies-map-editor-windows.msi"
tasks.register<Exec>("appInstaller") {
    group = "distribution"
    description = "Builds the Windows installer, build/installer/$installerName; needs WiX"
    dependsOn(appImage)
    // locals, as the configuration cache keeps no reference to the script
    val onWindows = isWindows
    val msiName = installerName
    onlyIf("an msi is made on Windows only") { onWindows }
    inputs.dir(appImageDir)
    inputs.dir("packaging/installer")
    inputs.property("appVersion", appVersion)
    outputs.dir(installerDir)
    val outputFolder = installerDir.get().asFile
    val pictures = file("packaging/installer")
    val resources = layout.buildDirectory.dir("installer-resources").get().asFile
    val jdkHome = jpackage.map { File(it).parentFile.parentFile.absolutePath }
    doFirst {
        outputFolder.deleteRecursively()
        resources.deleteRecursively()
        resources.mkdirs()
        val wxs = FileSystems
            .newFileSystem(URI.create("jrt:/"), mapOf("java.home" to jdkHome.get()))
            .use { jdk ->
                Files.readString(
                    jdk.getPath("/modules/jdk.jpackage/jdk/jpackage/internal/resources/main.wxs")
                )
            }
        val ui = "<UIRef Id=\"JpUI\"/>"
        check(wxs.contains(ui)) { "jpackage's main.wxs has no $ui to put the pictures before" }
        resources.resolve("main.wxs").writeText(
            wxs.replace(
                ui,
                listOf("WixUIBannerBmp" to "banner.bmp", "WixUIDialogBmp" to "dialog.bmp")
                    .joinToString("\n    ", postfix = "\n    $ui") { (id, picture) ->
                        "<WixVariable Id=\"$id\" Value=\"${pictures.resolve(picture).absolutePath}\"/>"
                    },
            )
        )
    }
    executable(jpackage.get())
    args(
        "--type", "msi",
        "--app-image", appImageDir.get().dir(appName).asFile.absolutePath,
        "--resource-dir", resources.absolutePath,
        "--name", appName,
        "--app-version", appVersion,
        "--vendor", "Eddie",
        "--description", "Map editor for TripleA",
        "--about-url", "https://github.com/xXEddieXxx/eddies-map-editor",
        "--win-per-user-install",
        "--win-menu",
        "--win-shortcut-prompt",
        "--win-upgrade-uuid", "6f0c9d0e-3b7a-4f58-9c1e-5a2d8e4b7c31",
        "--dest", outputFolder.absolutePath,
    )
    // jpackage names it by name and version; the release links to one name
    doLast {
        outputFolder.listFiles { f -> f.extension == "msi" }!!.single()
            .renameTo(outputFolder.resolve(msiName))
    }
}

tasks.register<Zip>("appZip") {
    group = "distribution"
    description = "Zips the program of appImage, eg build/distributions/eddies-map-editor-windows.zip"
    from(appImage)
    archiveFileName.set(
        "eddies-map-editor-" + (if (isWindows) "windows" else System.getProperty("os.name").lowercase()
            .substringBefore(' ')) + ".zip"
    )
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}
