// MongrelDB Kotlin/Native client build script.
//
// A Kotlin Multiplatform project targeting native (Linux, macOS, Windows).
// Two integration modes:
//
//   1. HTTP client (default) — talks to mongreldb-server over HTTP via
//      ktor-client-curl. The primary transport, matching the C/C++ clients.
//
//   2. Native embedded (Tier 1) — links libmongreldb_kit + libmongreldb
//      directly via cinterop, running the engine in-process with zero
//      serialization overhead. Requires the prebuilt native libraries on
//      the linker path (MONGRELDB_NATIVE_DIR or system search path).
//
// The cinterop + native linking is opt-in via the `-PenableNative=true`
// Gradle property. CI builds and tests the HTTP client + wire-shape tests
// without the native libs; the native mode is tested separately by
// downloading the prebuilt libs and setting the linker path.

plugins {
    kotlin("multiplatform") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    `maven-publish`
}

group = "com.visorcraft"
version = "0.64.2"

repositories {
    mavenCentral()
}

// Whether to enable cinterop against libmongreldb_kit. Requires the native
// libraries on the linker path. Set with: -PenableNative=true
val enableNative = (project.findProperty("enableNative") as? String)?.toBoolean() ?: false
val nativeLibraryDir = System.getenv("MONGRELDB_NATIVE_DIR")

kotlin {
    // Native targets matching the engine's prebuilt library matrix.
    linuxX64()
    macosX64()
    macosArm64()
    mingwX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-core:3.1.3")
                implementation("io.ktor:ktor-client-content-negotiation:3.1.3")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        // Native-specific: the ktor curl engine.
        val nativeMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation("io.ktor:ktor-client-curl:3.1.3")
            }
        }

        val nativeTest by creating {
            dependsOn(commonTest)
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
            }
        }

        // Wire each target's main/test source sets to the shared native ones.
        // Using string-based lookup because the per-target source sets are
        // registered lazily by the Kotlin/Native plugin.
        listOf("linuxX64", "macosX64", "macosArm64", "mingwX64").forEach { target ->
            getByName("${target}Main").dependsOn(nativeMain)
            getByName("${target}Test").dependsOn(nativeTest)
        }
    }

    // cinterop: generate Kotlin bindings from the Kit C ABI header.
    // Only enabled when -PenableNative=true (requires native libs at link time).
    if (enableNative) {
        targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
            compilations["main"].cinterops {
                val mongreldbKit by creating {
                    defFile = project.file("src/nativeInterop/cinterop/mongreldb_kit.def")
                    packageName = "com.visorcraft.mongreldb.native"
                    includeDirs(project.file("native-headers").absolutePath)
                }
            }
        }
    } else {
        // Exclude NativeDB.kt from compilation when cinterop is not enabled.
        // It references the generated FFI bindings which only exist when
        // -PenableNative=true.
        sourceSets.matching { it.name.endsWith("Main") }.all {
            kotlin.exclude("**/NativeDB.kt")
        }
    }

    // All targets compile as a klib (library). No executable by default.
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
        binaries.all {
            freeCompilerArgs += "-Xallocator=std"
            if (enableNative && nativeLibraryDir != null) {
                linkerOpts("-L$nativeLibraryDir")
            }
        }
    }
}

// ── Maven Central publishing metadata ───────────────────────────────────────
publishing {
    publications {
        publications.withType<MavenPublication>().configureEach {
            pom {
                name.set("MongrelDB Kotlin/Native Client")
                description.set(
                    "Kotlin/Native HTTP + native embedded client for MongrelDB. " +
                    "Compiles to native machine code (no JVM). HTTP mode talks to " +
                    "mongreldb-server; native mode links libmongreldb_kit for " +
                    "in-process engine access."
                )
                url.set("https://github.com/visorcraft/MongrelDB-Kotlin-Native")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        name.set("Visorcraft")
                        email.set("support@visorcraft.com")
                        organization.set("Visorcraft")
                        organizationUrl.set("https://www.visorcraft.com")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/visorcraft/MongrelDB-Kotlin-Native.git")
                    developerConnection.set("scm:git:https://github.com/visorcraft/MongrelDB-Kotlin-Native.git")
                    url.set("https://github.com/visorcraft/MongrelDB-Kotlin-Native")
                }
            }
        }
    }
}
