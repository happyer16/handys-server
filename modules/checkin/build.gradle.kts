plugins {
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation(project(":module-common"))
    implementation(project(":module-property"))
    implementation(project(":module-booking"))
    implementation("org.springframework:spring-context")
}
