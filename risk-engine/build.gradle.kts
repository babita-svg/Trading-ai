plugins {
    alias(libs.plugins.kotlin.jvm)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}

// Enforce zero Android and zero Retrofit dependencies at compile time.
configurations.all {
    incoming.beforeResolve {
        dependencies.forEach { dep ->
            val group = dep.group ?: return@forEach
            check(!group.startsWith("com.squareup.retrofit2")) {
                ":risk-engine must not depend on Retrofit"
            }
            check(!group.startsWith("com.android")) {
                ":risk-engine must not depend on Android"
            }
        }
    }
}
