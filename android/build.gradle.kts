// Versions are pinned and chosen to work together:
//   Gradle 8.10.2 (set in .github/workflows/build.yml)
//   Android Gradle Plugin 8.7.3  -> needs Gradle >= 8.9 and JDK 17
//   Kotlin 2.1.0                 -> tested with Gradle <= 8.10 and AGP 8.7
// All of them still build apps with minSdk 21 (Android 5.0).
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}
