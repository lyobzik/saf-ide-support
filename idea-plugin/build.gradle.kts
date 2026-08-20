import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

/**
 * Базовое имя артефакта релиза. Обе стороны называются одинаково
 * (`smartapp-dsl-<версия>.zip` и `smartapp-dsl-<версия>.vsix`), и по этому же
 * имени упаковка отличает свои файлы в `dist/` от чужих.
 */
val artifactName = "smartapp-dsl"

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
    projectName = artifactName

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

/** Общий каталог артефактов релиза: сюда же расширение кладёт свой `.vsix`. */
val distDir = rootProject.layout.projectDirectory.dir("dist")

/** Промежуточный каталог упаковки — тот же, через который проходит `.vsix`. */
val stagingDir = distDir.dir(".staging")

/**
 * Кладёт собранный zip плагина в общий каталог `dist/` — туда же, куда
 * расширение кладёт свой `.vsix`. Сборку по-прежнему делает `buildPlugin`;
 * здесь только публикация, чтобы у релиза монорепозитория было одно место, а не
 * каталоги двух разных систем сборки.
 *
 * Копирование идёт в `dist/.staging/`, а в `dist/` файл попадает переносом —
 * той же атомарной заменой, что и `.vsix`. Прямое копирование в `dist/` может
 * прерваться (остановленный процесс, ошибка ФС) и оставить опубликованным
 * обрезанный архив: при совпадении версий — поверх рабочего.
 */
val packagePlugin by tasks.registering(Copy::class) {
    group = "build"
    description = "Собирает zip плагина и кладёт его в dist/"
    from(tasks.named("buildPlugin"))
    into(stagingDir)

    // Задача всегда выполняется: иначе на «актуальном» прогоне не отработает
    // публикация и уборка. Копирование одного файла того не стоит, чтобы на
    // нём экономить.
    outputs.upToDateWhen { false }

    val artifacts = distDir.asFile
    val staging = stagingDir.asFile
    val currentZip = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFileName }

    // Остатки прерванного прогона: незавершённый архив не должен попасть в
    // релиз ни при каком следующем запуске.
    doFirst { staging.deleteRecursively() }

    doLast {
        val name = currentZip.get()
        Files.move(
            staging.resolve(name).toPath(),
            artifacts.resolve(name).toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        staging.deleteRecursively()

        // Дистрибутив прошлой версии рядом с новым — это шанс поставить или
        // выложить не тот файл: и упаковка, и CI забирают zip маской
        // `dist/*.zip`. Убираем после публикации и только своё: `dist/` —
        // каталог релиза, но не собственность этой сборки, и чужой архив тут
        // удалять не за что.
        artifacts.listFiles { file ->
            file.isFile &&
                file.name != name &&
                file.name.startsWith("$artifactName-") &&
                file.name.endsWith(".zip")
        }?.forEach { it.delete() }
    }
}
