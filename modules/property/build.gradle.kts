plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
}

dependencies {
    implementation(project(":module-common"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
}
