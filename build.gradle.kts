import org.gradle.internal.os.OperatingSystem
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.lilyanmuller.rider"
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // Real JVM default methods only: no delegating overrides of platform interface defaults (which the
        // Plugin Verifier reports as usages of deprecated or experimental API).
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

/** The local Rider to compile, test and run against: -PriderLocalPath=..., else the usual install locations. */
val riderLocalPath: String = providers.gradleProperty("riderLocalPath").orNull
    ?: listOf("${System.getProperty("user.home")}/Applications/Rider.app", "/Applications/Rider.app").firstOrNull { file(it).exists() }
    ?: error("Rider 2026.2 not found: pass -PriderLocalPath=/path/to/Rider.app")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local(riderLocalPath)
        pluginVerifier()
    }
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    pluginVerification {
        ides {
            local(file(riderLocalPath))
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        changeNotes = """
            <ul>
              <li>Frosted window and Islands panes, Liquid Glass buttons and tabs (macOS 26+).</li>
              <li>Popups, menus, floating tool windows and detached editor windows share the glass.</li>
              <li>Follows the Rider theme and macOS Reduce Transparency / Increase Contrast; pauses in Presentation
                  Mode and with non-Islands themes.</li>
              <li>Installs, updates and uninstalls without restart; safe mode after an abnormal stop.</li>
            </ul>
        """.trimIndent()
        ideaVersion {
            sinceBuild = "262.10315"
            untilBuild = "262.*"
        }
    }
}

val nativeOutput = layout.buildDirectory.dir("native")

val buildNativeBridge by tasks.registering(Exec::class) {
    description = "Compiles the AppKit bridge (libliquidglass.dylib) as a universal arm64 + x86_64 binary."
    onlyIf { OperatingSystem.current().isMacOsX }
    inputs.dir("native/src")
    inputs.file("native/build.sh")
    outputs.dir(nativeOutput)
    commandLine("native/build.sh", nativeOutput.get().asFile.absolutePath)
}

tasks.withType<PrepareSandboxTask>().configureEach {
    from(buildNativeBridge) {
        into(intellijPlatform.projectName.map { "$it/native" })
    }
}

tasks {
    runIde {
        // The project file, not its folder: a folder makes Rider ask which project to open.
        args(layout.projectDirectory.file("sample/HelloGlass/HelloGlass.csproj").asFile.absolutePath)
        // The sandbox has no user VM options file, so it gets the presentation fix the plugin offers to users
        // (see PresentationSync) directly; -PrlgJvmArgs=... replaces it for experiments.
        val extraJvmArgs = providers.gradleProperty("rlgJvmArgs").orElse("-Dsun.java2d.metal.displaySync=false").get()
        jvmArgs(extraJvmArgs.split(' ').filter { it.isNotBlank() })
        // The sandbox marks the plugin as required and pins it with -Dplugin.path, which both forbid unloading
        // it. Without them the plugin is loaded like any installed plugin, and the IDE's auto-reload unloads and
        // reloads it after each `prepareSandbox`: the dynamic plugin path, tested for real on every build.
        doFirst {
            val task = this as JavaExec
            val sandbox = task.jvmArgumentProviders.filter { it.javaClass.simpleName.endsWith("SandboxArgumentProvider") }
            val sandboxArgs = sandbox.flatMap { it.asArguments() }.filterNot { it.startsWith("-Dplugin.path=") }
            task.jvmArgumentProviders.removeAll(sandbox.toSet())
            task.jvmArgumentProviders.removeIf { it.javaClass.simpleName.endsWith("PluginArgumentProvider") }
            task.jvmArgs(sandboxArgs)
        }
    }
    test {
        useJUnitPlatform()
        dependsOn(buildNativeBridge)
        // Test layout mirrors the plugin: <root>/native/libliquidglass.dylib
        systemProperty("rlg.testPluginRoot", layout.buildDirectory.get().asFile.absolutePath)
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}
