plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

// 发布坐标 com.github.mif9499:scroll:<version>；JitPack 构建时注入 VERSION=<tag>
group = "com.github.mif9499"
version = System.getenv("VERSION") ?: "0.1.0"

android {
    namespace = "scroll"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// 公开 API 里出现 Compose 类型（FlingBehavior / OverscrollEffect / DecayAnimationSpec / Modifier …），
// 所以这些依赖要给到使用方的编译期，不能只做 implementation。
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.runtime)
    api(libs.androidx.compose.animation)
    api(libs.androidx.foundation)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                pom {
                    name.set("scroll")
                    description.set("Cupertino 手感的 Compose 滚动：惯性 + 越界回弹")
                    url.set("https://github.com/mif9499/scroll")
                    licenses {
                        license {
                            name.set("MIT")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                }
            }
        }
    }
}
