plugins {
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation(project(":module-common"))
    implementation("org.springframework:spring-context")
}
