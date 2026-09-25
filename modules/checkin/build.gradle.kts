plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
}

dependencies {
    implementation(project(":module-common"))
    implementation(project(":module-property"))
    implementation(project(":module-booking"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
}
