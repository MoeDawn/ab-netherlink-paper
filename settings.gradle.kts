plugins {
    // Foojay 解析器：让 Gradle 自动下载缺失的 JDK 工具链（本机仅 JDK 22，Paper 26.3 需要 25）
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "netherlink-paper"
