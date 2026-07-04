import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.composeMultiplatform)
    id("maven-publish")
}

group = "io.github.femimarket"
version = project.findProperty("libraryVersion") as String? ?: "local-dev"

kotlin {
    androidLibrary {
        namespace = "market.femi.imageedit"
        compileSdk = 37
        minSdk = 33

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
    js {
        browser()
        useEsModules()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            // ProjectService — exposed to consumers (the host reads the arg
            // back with getImageEdit() after onTrigger fires).
            api(libs.femi.api)
        }
        // No shared UI: commonMain stays empty. Each platform ships its own
        // full implementation of the ImageEdit screen.
        androidMain.dependencies {
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.compose.material3)
            implementation(libs.androidx.compose.material.icons.extended)
            implementation(libs.androidx.compose.ui)
            implementation(libs.androidx.compose.ui.graphics)
            implementation(libs.androidx.compose.ui.tooling.preview)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.exifinterface)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }
        webMain.dependencies {
            // Compose Multiplatform (canvas) implementation shared by js + wasmJs.
            // Platform helpers (OPFS reads, file pickers, blob URLs) come from
            // the api itself since 4.10.x.
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.materialIconsExtended)
            implementation(libs.cmp.material3)
            implementation(libs.kotlinx.browser)
        }
    }
}

// The Kotlin Multiplatform plugin auto-creates the publications (one per target + a root);
// we just point them at GitHub Packages, matching Api2/Kmp.
// Publish with: ./gradlew publish -PlibraryVersion=<x.y.z>
publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/femimarket/Edit")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
