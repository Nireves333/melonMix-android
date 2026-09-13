object AppConfig {
    const val compileSdkVersion = 37
    const val targetSdkVersion = compileSdkVersion
    const val minSdkVersion = 24
    const val ndkVersion = "28.0.13004108"

    // [KHMM] Melon Mix has its own identity and versioning, starting at 1.0.0
    // (forked from melonDS-android 2.0.1 / versionCode 41)
    const val versionCode = 3
    const val versionName = "1.0.2"
}