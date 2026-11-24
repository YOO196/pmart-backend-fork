// MFC 수요예측·보충추천 데모 배치. Java 21 + Spring Boot(루트 카탈로그 버전) + Spring Batch + PostgreSQL.
plugins {
    java
    alias(libs.plugins.spring.boot)
}

group = "com.pmart.demo"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get().toInt()))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(libs.versions.java.get().toInt())
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial"))
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.batch)
    implementation(libs.spring.boot.starter.batch.jdbc)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(platform(libs.spring.boot.bom))
                implementation.bundle(libs.bundles.test.unit)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(platform(libs.spring.boot.bom))
                implementation.bundle(libs.bundles.test.unit)
                implementation(libs.spring.boot.starter.test)
                implementation(libs.spring.boot.testcontainers)
                implementation(libs.testcontainers.junit.jupiter)
                implementation(libs.testcontainers.postgresql)
                runtimeOnly(libs.junit.platform.launcher)
            }
            targets.all {
                testTask.configure { shouldRunAfter(tasks.named("test")) }
            }
        }
    }
}

// 통합 시험은 본 코드의 의존성(Spring Batch·JDBC)을 그대로 쓴다.
configurations.named("integrationTestImplementation") { extendsFrom(configurations.implementation.get()) }
configurations.named("integrationTestRuntimeOnly") { extendsFrom(configurations.runtimeOnly.get()) }

tasks.withType<Test>().configureEach {
    systemProperty("file.encoding", "UTF-8")
    systemProperty("user.timezone", "UTC")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named("check") { dependsOn(testing.suites.named("integrationTest")) }

// 합성 샘플 CSV 만들기(다운로드 없이 기본 배치 검수용). 결과: sample/uci-synthetic.csv
tasks.register<JavaExec>("generateSample") {
    group = "demo"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pmart.demo.replenishment.sample.SyntheticUciSample")
    args("sample/uci-synthetic.csv")
    jvmArgs("-Dstdout.encoding=UTF-8")
}

// PMart 취급 상품(503 SKU)에 맞춘 합성 판매·시나리오. 결과: build/pmart-sample/(sales.csv, scenario/)
tasks.register<JavaExec>("generatePmartSample") {
    group = "demo"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pmart.demo.replenishment.sample.PmartSample")
    args("sample/pmart", "build/pmart-sample")
    jvmArgs("-Dstdout.encoding=UTF-8")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs("-Dstdout.encoding=UTF-8", "-Duser.timezone=UTC")
}
