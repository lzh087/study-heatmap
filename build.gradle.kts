plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.study.heatmap"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 与本机 IDEA Community 2025.2 对齐，避免编译期 API 与运行期不一致
        intellijIdeaCommunity("2025.2")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

intellijPlatform {
    buildSearchableOptions = false
    // 不做 @NotNull 字节码插桩，省掉 instrumentationTools 依赖
    instrumentCode = false

    pluginConfiguration {
        id = "com.study.heatmap"
        name = "Study Heatmap"
        version = "0.1.0"
        description = """
            统计你在代码上真实停留了多久，并在退出项目时生成一份按行染色的热力图。

            · 屏幕上可见的每一行代码，每秒各记 1 秒
            · 滚动、窗口失焦、键鼠（含触控板）10 分钟无操作都不计入，离开的部分会自动回退
            · 关闭项目时弹窗选择「保留，下次继续」或「结算并生成报告」
            · 报告输出到项目下的 study-report/，与源码目录 1:1 对应的 HTML 镜像
        """.trimIndent()

        ideaVersion {
            sinceBuild = "252"
            untilBuild = "261.*"
        }
    }
}
