import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    android {
        namespace = "com.clhs.score.shared"
        compileSdk = 37
        minSdk = 29
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        withHostTestBuilder {}
    }

    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "PocketShared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
