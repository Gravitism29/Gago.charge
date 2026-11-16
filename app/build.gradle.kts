import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 서명 정보는 git 에 넣지 않는다. 루트의 keystore.properties(.gitignore 대상)가 있을 때만 릴리스 서명을 건다.
//   storeFile=...  storePassword=...  keyAlias=...  keyPassword=...
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

val useFakesByDefault = (findProperty("gakim.useFakes") as String?)?.toBoolean() ?: true
val localSecrets = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun apiKey(property: String, environment: String): String {
    val value = localSecrets.getProperty(property)?.takeIf { it.isNotBlank() }
        ?: System.getenv(environment).orEmpty()
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "") + "\""
}

android {
    useLibrary("android.car")
    namespace = "kr.gakim.charge"
    // 기본은 공식 예제와 같은 35. 설치된 SDK 구성이 다를 때만 아래 속성으로 바꿔 빌드한다.
    //   -Pgakim.compileSdk=NN  -Pgakim.compileSdkExtension=NN (예: platforms/android-35-ext15)  -Pgakim.buildTools=x.y.z
    compileSdk = (findProperty("gakim.compileSdk") as String?)?.toInt() ?: 35
    (findProperty("gakim.compileSdkExtension") as String?)?.let { compileSdkExtension = it.toInt() }
    (findProperty("gakim.buildTools") as String?)?.let { buildToolsVersion = it }

    defaultConfig {
        applicationId = "kr.gakim.charge"
        minSdk = 28 // 공모전 요건
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.0"
        buildConfigField("String", "KAKAO_REST_API_KEY", apiKey("kakao.restApiKey", "KAKAO_REST_API_KEY"))
        buildConfigField("String", "DATA_GO_KR_SERVICE_KEY", apiKey("datagokr.serviceKey", "DATAGOKR_SERVICE_KEY"))
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Pleos 에뮬레이터가 아닌 곳에서도 화면 흐름을 볼 수 있게 Fake 로 시작한다(-Pgakim.useFakes=false 로 변경).
            // BuildConfig 상수는 Kotlin 증분 컴파일에서 옛 값이 코드에 남는 일이 있어(2026-11-14 확인) 리소스로 읽는다.
            resValue("bool", "use_fakes_default", useFakesByDefault.toString())
        }
        release {
            isMinifyEnabled = false
            // 릴리스는 항상 실제 Pleos SDK 로 시작한다. 앱 안의 스위치로 Fake 로 바꿀 수는 있다.
            resValue("bool", "use_fakes_default", "false")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":domain"))

    // Pleos Connect SDK (https://nexus-playground.pleos.ai/repository/maven-releases/)
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
