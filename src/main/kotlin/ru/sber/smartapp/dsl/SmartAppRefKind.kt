package ru.sber.smartapp.dsl

/**
 * Виды именованных сущностей, определяемых JSON-файлами SmartApp DSL в
 * `static/references/<dir>/`. Каждый вид соответствует одному подкаталогу и
 * является единицей межфайлового резолва определений/ссылок.
 */
enum class SmartAppRefKind(val dirName: String) {
    SCENARIO("scenarios"),
    FORM("forms"),
    ACTION("actions"),
    BEHAVIOR("behaviors"),
    FILLER("field_fillers"),
    CLASSIFIER("classifiers");

    companion object {
        private val byDir: Map<String, SmartAppRefKind> = entries.associateBy { it.dirName }

        fun forDir(dir: String): SmartAppRefKind? = byDir[dir]
    }
}
