import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}

rootProject.name = "gakim-charge"

// :domain 은 순수 Kotlin/JVM 모듈이라 Android SDK 없이도 테스트할 수 있다.
include(":domain")

// :app 은 Android SDK 가 있을 때만 포함한다. SDK 가 없는 PC 에서도
// `gradlew :domain:test` 가 설정 단계에서 실패하지 않게 하기 위해서다.
fun findAndroidSdk(): File? {
    val candidates = mutableListOf<String?>()
    val localProps = File(rootDir, "local.properties")
    if (localProps.isFile) {
        val props = Properties()
        localProps.inputStream().use { props.load(it) }
        candidates += props.getProperty("sdk.dir")
    }
    candidates += System.getenv("ANDROID_HOME")
    candidates += System.getenv("ANDROID_SDK_ROOT")
    System.getenv("LOCALAPPDATA")?.let { candidates += "$it/Android/Sdk" }
    candidates += System.getProperty("user.home") + "/Android/Sdk"
    candidates += System.getProperty("user.home") + "/Library/Android/sdk"
    return candidates.filterNotNull().map { File(it) }.firstOrNull { File(it, "platforms").isDirectory }
}

val androidSdk = findAndroidSdk()
if (androidSdk != null) {
    include(":app")
} else {
    logger.warn(
        "[gakim-charge] Android SDK 를 찾지 못해 :app 모듈을 제외했습니다. " +
            "Android Studio 첫 실행 마법사로 SDK 를 설치하면 자동으로 포함됩니다. (:domain 은 그대로 빌드·테스트 가능)"
    )
}
