// MFC 수요예측·보충추천 데모(명세: README.md). PMart 운영 서비스와 따로 도는 독립 Gradle 빌드다.
// 루트 멀티프로젝트에는 등록하지 않는다. 버전은 루트 카탈로그(gradle/libs.versions.toml)를 그대로 읽는다.
// 실행: 루트에서 ./gradlew -p demos/mfc-demand-replenishment <task>

rootProject.name = "mfc-demand-replenishment"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}
