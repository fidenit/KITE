plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fidenit.kite"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fidenit.kite"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.1"
    }
    // 두 갈래: free 는 사용자가 넣은 키로 폰이 Claude API 를 직접 부르고,
    // pro 는 아무것도 넣지 않고 우리 중계 서버(비공개 저장소)를 거친다. 코드는 같고 BuildConfig.PRO 로 갈린다.
    flavorDimensions += "track"
    productFlavors {
        create("free") {
            dimension = "track"
            buildConfigField("boolean", "PRO", "false")
            buildConfigField("String", "RELAY_URL", "\"\"")
            resValue("string", "app_name", "APERIT")
        }
        create("pro") {
            dimension = "track"
            applicationIdSuffix = ".pro"
            buildConfigField("boolean", "PRO", "true")
            // 중계 서버 주소는 빌드할 때 정한다: ./gradlew -PkiteRelayUrl=https://... assembleProDebug
            val relay = (project.findProperty("kiteRelayUrl") as String?) ?: "http://10.0.2.2:8787"
            buildConfigField("String", "RELAY_URL", "\"$relay\"")
            resValue("string", "app_name", "APERIT Pro")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += setOf("META-INF/versions/**", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST")
        }
    }
    // 모델 지시문은 데스크톱과 같은 파일(src/prompt.md)을 쓴다. 빌드할 때 복사해 온다.
    sourceSets["main"].assets.srcDir("build/generated/kiteAssets")
}

val copyPrompt = tasks.register<Copy>("copyPrompt") {
    from("../../src/prompt.md")
    into(layout.buildDirectory.dir("generated/kiteAssets"))
}
tasks.named("preBuild") { dependsOn(copyPrompt) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("com.github.mwiede:jsch:0.2.21")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // Claude API: 공식 SDK. pro 는 baseUrl 을 중계 서버로 바꿔 같은 코드로 부른다.
    implementation("com.anthropic:anthropic-java:3.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // API 엔진 시험: 가짜 Claude 서버로 한 턴을 돌려 본다 (SDK 가 쓰는 OkHttp 와 같은 판)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
