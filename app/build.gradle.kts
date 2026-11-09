plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.quizgate.spike"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.quizgate.spike"
        minSdk = 26
        targetSdk = 34
        versionCode = providers.gradleProperty("quizgateVersionCode").orNull?.toInt() ?: 7
        versionName = providers.gradleProperty("quizgateVersionName").orNull ?: "0.6-client-hardening"
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"" + (providers.gradleProperty("quizgateUpdateManifestUrl").orNull ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    sourceSets {
        named("main") {
            // protoc 产物不入库到 java/ 里：它是从 ../anti-addiction/proto/quizgate.proto 生成的，
            // 和 src/main/java 混在一棵树会让人以为可以手改。重新生成跑 tools/gen-proto.sh。
            java.srcDir("src/main/java-gen")
        }
    }
}

dependencies {
    // protobuf 运行时走本地 jar：离线构建机上 gradle 缓存里没有 protobuf，
    // 而后端用的是同一份 javalite——版本必须对齐，否则 wire 格式两边对不上还没人报错。
    implementation(files("libs/protobuf-javalite-4.36.2.jar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
