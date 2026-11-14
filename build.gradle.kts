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
}

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
    // jpackage refuses to write over an earlier image
    doFirst { outputFolder.deleteRecursively() }
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
