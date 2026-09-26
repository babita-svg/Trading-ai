import java.util.Properties

val envFile = rootProject.file(".env")
if (!envFile.exists()) {
    throw GradleException(
        ".env file not found at ${envFile.absolutePath}. " +
        "Create it with: GEMINI_API_KEY=your_key_here"
    )
}
val envProps = Properties().apply { load(envFile.inputStream()) }
val geminiKey = envProps.getProperty("GEMINI_API_KEY")
    ?: throw GradleException("GEMINI_API_KEY not set in .env")

ext["geminiApiKey"] = geminiKey

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}