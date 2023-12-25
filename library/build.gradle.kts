plugins {
  alias(libs.plugins.androidLibrary)
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.kotlinxSerialization)
}

kotlin {
  jvmToolchain(20)

  jvm()

  androidTarget()

  listOf(
    iosX64(),
    iosArm64(),
    iosSimulatorArm64()
  ).forEach { iosTarget ->
    iosTarget.binaries.framework {
      baseName = "readability"
      isStatic = true
    }
  }

  sourceSets {
    commonMain.dependencies {
      implementation(libs.ksoup)
      implementation(libs.uri)
      implementation(libs.kotlinxSerialization.json)
    }
  }
}

android {
  namespace = "dev.sasikanth.readability"
  compileSdk = libs.versions.android.compileSdk.get().toInt()
  defaultConfig {
    minSdk = libs.versions.android.minSdk.get().toInt()
  }
}
