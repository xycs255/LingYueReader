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
        // 离线语音引擎 sherpa-onnx：官方只在 GitHub 发布 AAR，没有上 Maven 仓库。
        exclusiveContent {
            forRepository {
                ivy {
                    name = "sherpa-onnx releases"
                    url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/")
                    patternLayout { artifact("v[revision]/[module]-[revision].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.k2fsa", "sherpa-onnx") }
        }
    }
}

rootProject.name = "LingYueReader"
include(":app")
