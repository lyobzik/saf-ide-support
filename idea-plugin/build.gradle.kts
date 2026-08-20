import org.gradle.api.tasks.options.Option
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "ru.sber.smartapp"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

/**
 * Источник платформы IntelliJ:
 *  - `local` (по умолчанию) — локально установленная IDE по пути `localIdePath`;
 *  - `maven` — IntelliJ IDEA Community версии `platformVersion` из репозиториев.
 *
 * CI работает только со вторым вариантом: на Linux-runner'е локальной IDE нет.
 * `localIdePath` при `ideSource=maven` не читается вовсе, чтобы дефолтное
 * macOS-значение из gradle.properties не ломало сборку.
 */
val ideSource: String = providers.gradleProperty("ideSource").getOrElse("local")
val platformVersion: String = providers.gradleProperty("platformVersion").getOrElse("2025.1")
val pluginSinceBuild: String = providers.gradleProperty("pluginSinceBuild").get()

/**
 * Узкий classpath для экспортёра контракта: только собственные классы, stdlib и
 * gson. Платформа IDEA экспортёру не нужна (таблицы контракта её не импортируют),
 * поэтому CI-джоба контракта не тянет дистрибутив IDE.
 */
val contractExport: Configuration by configurations.creating

dependencies {
    contractExport("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    contractExport("com.google.code.gson:gson:2.11.0")

    intellijPlatform {
        when (ideSource) {
            "local" -> local(providers.gradleProperty("localIdePath").get())
            "maven" -> intellijIdeaCommunity(platformVersion)
            else -> error("Unknown ideSource '$ideSource': expected 'local' or 'maven'")
        }
        bundledPlugin("com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
    }
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    // Имя модуля (`idea-plugin`) описывает его место в монорепозитории, а имя
    // артефакта должно остаться прежним: smartapp-dsl-<версия>.zip.
    projectName = "smartapp-dsl"

    pluginConfiguration {
        ideaVersion {
            sinceBuild = pluginSinceBuild
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
}

/**
 * Словарь ключевых слов лежит в общем каталоге `shared/keywords/` (его читает и
 * VS Code-расширение) и копируется в ресурсы плагина на этапе сборки, поэтому
 * classpath-путь `/keywords/keywords.json` остаётся прежним.
 */
tasks.processResources {
    from(rootProject.layout.projectDirectory.dir("shared/keywords")) {
        // Схема нужна только проверкам сборки/CI — в бандл плагина она не идёт.
        include("keywords.json")
        into("keywords")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.test {
    useJUnit()
    // Общие артефакты — полноценный вход тестов: без этого Gradle считает
    // задачу актуальной после правки фикстур или контракта, и conformance-прогон
    // молча остаётся зелёным на устаревших результатах.
    inputs.dir(rootProject.layout.projectDirectory.dir("shared"))
        .withPropertyName("sharedContract")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Тесты контракта читают общие артефакты (rules.json, keywords.json, фикстуры)
    // напрямую из shared/, а не из ресурсов плагина.
    systemProperty("smartapp.shared", rootProject.layout.projectDirectory.dir("shared").asFile.absolutePath)
}

/**
 * Экспорт контракта данных в `shared/rules/rules.json`.
 *
 * Сериализуются те же Kotlin-таблицы, что использует рантайм плагина, поэтому
 * снимок не может разойтись с поведением. Режим `--check` ничего не пишет и
 * падает, если закоммиченный снимок устарел (используется в CI).
 */
abstract class ExportRulesTask : JavaExec() {

    @get:Internal
    abstract val outputFile: RegularFileProperty

    @get:Internal
    var check: Boolean = false

    @Option(option = "check", description = "Verify the committed snapshot instead of rewriting it")
    fun useCheck(value: Boolean) {
        check = value
    }

    override fun exec() {
        args = buildList {
            if (check) add("--check")
            add(outputFile.get().asFile.absolutePath)
        }
        super.exec()
    }
}

val exportRules by tasks.registering(ExportRulesTask::class) {
    group = "verification"
    description = "Экспортирует контракт данных SmartApp DSL в shared/rules/rules.json"
    classpath = files(sourceSets.main.get().output, contractExport)
    mainClass = "ru.sber.smartapp.dsl.contract.ExportRules"
    outputFile.set(rootProject.layout.projectDirectory.file("shared/rules/rules.json"))
}
