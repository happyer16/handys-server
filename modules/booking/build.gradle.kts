plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
}

dependencies {
    implementation(project(":module-common"))
    implementation(project(":module-property"))
    implementation(project(":module-inventory"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework:spring-tx")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}
