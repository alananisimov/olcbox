import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.serialization)
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("org.olcbox.daemon.MainKt")
}

// This daemon only ever targets Linux (root systemd system-service) — the
// application plugin's startScripts task generates a Windows .bat launcher
// unconditionally regardless of target platform. Excluding on
// application.applicationDistribution didn't actually reach installDist's
// output (its CopySpec wiring doesn't cascade the way that suggests);
// excluding directly on the installDist Sync task itself is what actually
// works, since that's the exact task producing the output we ship.
tasks.named<Sync>("installDist") {
    exclude("**/*.bat")
}

dependencies {
    implementation(project(":daemonIpc"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.double.receive)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.simple)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
}

tasks.test {
    useJUnitPlatform()
}

// Bundles hev-socks5-tunnel + olcrtc into this module's own installDist
// output, reusing desktopApp's existing Go/C build tasks instead of
// duplicating them here — Go/C compilation stays defined in exactly one
// place (desktopApp/build.gradle.kts).
val hostArch = when (System.getProperty("os.arch").lowercase()) {
    "x86_64", "amd64" -> "amd64"
    "aarch64", "arm64" -> "arm64"
    else -> error("Unsupported daemon architecture: ${System.getProperty("os.arch")}")
}
val olcRtcBuildTaskName = if (hostArch == "amd64") "buildOlcRtcLinuxAmd64" else "buildOlcRtcLinuxArm64"
val desktopNativeResourcesDir = project(":desktopApp").layout.buildDirectory.dir("generated/desktopNativeResources/native")

// installDist (application plugin) is a Sync task — it deletes anything in
// its output dir that it didn't itself produce. This task must therefore
// run AFTER installDist (via dependsOn + the finalizedBy hook below), never
// before, or its copied binaries would be wiped on the next installDist run.
val bundleDaemonNativeAssets by tasks.registering(Copy::class) {
    dependsOn("installDist", ":desktopApp:buildHevSocks5TunnelLinux", ":desktopApp:$olcRtcBuildTaskName")
    from(desktopNativeResourcesDir) {
        include("olcrtc-linux-$hostArch", "hev-socks5-tunnel-linux-$hostArch")
        rename("hev-socks5-tunnel-linux-$hostArch", "hev-socks5-tunnel")
        filePermissions { unix("0755") }
    }
    into(layout.buildDirectory.dir("install/daemon/bin"))
}

// OlcRtcCommand.yaml() (built client-side and sent to the daemon as-is)
// includes a `data:` path pointing at olcrtc's names/surnames files — the
// daemon needs its own copy at a fixed location (DaemonPaths.DATA_DIR) since
// it runs its own separate olcrtc binary, independent of whatever the GUI
// itself has bundled.
val bundleDaemonDataAssets by tasks.registering(Copy::class) {
    dependsOn("installDist", ":desktopApp:copyOlcRtcDataAssets")
    from(project(":desktopApp").layout.buildDirectory.dir("generated/desktopNativeResources/olcrtc-data")) {
        include("names", "surnames")
    }
    into(layout.buildDirectory.dir("install/daemon/data"))
}

tasks.named("installDist") {
    finalizedBy(bundleDaemonNativeAssets, bundleDaemonDataAssets)
}
