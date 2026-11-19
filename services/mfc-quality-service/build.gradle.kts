// mfc-quality-service: docs/architecture/service-map.md 참조. 업무 규칙은 domain/application 패키지에.
plugins {
    id("pmart.spring-service")
}

pmartOpenApi {
    basePackage.set("com.pmart.mfc.quality")
}

dependencies {
    implementation(project(":libraries:common-web"))
    implementation(project(":libraries:common-observability"))
    implementation(project(":libraries:common-security"))
    implementation(project(":libraries:common-messaging"))
    implementation(libs.bundles.service.kafka)
    implementation(libs.bundles.service.data)
    implementation(libs.bundles.service.observability)
    // 품질 증빙 사진 저장소(0.9.0, PDA BR-52 R18-2): S3 호환(로컬·LAN SeaweedFS) — delivery-execution 과 같은 방식, netty 는 뺀다(ADR-0016)
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }

    testImplementation(project(":libraries:common-test"))
    "integrationTestImplementation"(project(":libraries:common-test"))
    "integrationTestImplementation"(libs.spring.boot.starter.jdbc) // 시험에서 Outbox 행 확인·sweep 시각 당기기
    "integrationTestImplementation"(libs.wiremock.spring.boot) // mfc-inventory 품질 반영 응답 고정
    "integrationTestImplementation"(libs.bundles.service.kafka) // 시험에서 ReturnItemReceived 를 토픽에 흘림
}
