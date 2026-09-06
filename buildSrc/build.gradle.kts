// buildSrc：承载构建期可单测的纯逻辑（R4 DEX registers 解析器等）。
// 该模块的类自动出现在主构建的 build classpath 上，composeApp/build.gradle.kts 可直接 import。
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
