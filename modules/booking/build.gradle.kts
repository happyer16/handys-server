plugins {
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation(project(":module-common"))
    implementation(project(":module-property"))
    implementation(project(":module-inventory"))
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}
