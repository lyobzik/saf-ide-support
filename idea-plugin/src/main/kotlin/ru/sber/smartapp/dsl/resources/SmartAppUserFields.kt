package ru.sber.smartapp.dsl.resources

import com.google.gson.JsonParser
import ru.sber.smartapp.dsl.contract.UserModelSpec
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Снимок полей и атрибутов модели пользователя **фреймворка** — пол, поверх
 * которого работает половина приложения.
 *
 * Ресурс создаётся скриптом `tools/generate_user_fields.py` из вендоренных
 * модулей и копируется в ресурсы плагина на сборке (`processResources`), так же
 * как словарь ключевых слов. Без него фича не даёт ничего на типовом
 * приложении: `CustomUser.fields` возвращает `super().fields + []`, то есть все
 * имена приходят из библиотеки.
 */
object SmartAppUserFields {

    private const val RESOURCE = "/keywords/user_fields.json"

    /**
     * Имена одного библиотечного класса.
     *
     * [fields] и [attributes] разделены не для красоты: [fields] создаёт
     * `Model.__init__` из списка `fields`, и они **отбрасываются**, если класс
     * приложения переопределил свойство без `super().fields`; [attributes]
     * свойством не управляются и отбрасыванию не подлежат.
     *
     * [diagnosticsSafe] — решение, принятое при вендоринге: можно ли включать
     * WARNING поверх этого пола. Не вычисляется гасителями (см. комментарий в
     * генераторе).
     */
    data class ClassSnapshot(
        val fields: Set<String>,
        val attributes: Set<String>,
        val diagnosticsSafe: Boolean,
    )

    /** Точечное имя класса -> его имена. Ключ — то же значение, что `ChainResult.libraryBase`. */
    val byClass: Map<String, ClassSnapshot> by lazy { load() }

    /** Снимок библиотечной базы или `null`, если про неё ничего не известно. */
    fun of(libraryBase: String?): ClassSnapshot? = libraryBase?.let { byClass[it] }

    /**
     * Загружает снимок, **падая громко** при отсутствии, пустоте или отсутствии
     * секции `classes`.
     *
     * Тихая деградация здесь недопустима по той же причине, что у
     * [ru.sber.smartapp.dsl.SmartAppKeywords]: неверно настроенный
     * `processResources` выключил бы семантику модели пользователя целиком, и
     * ни один тест этого бы не заметил.
     */
    private fun load(): Map<String, ClassSnapshot> {
        val stream = SmartAppUserFields::class.java.getResourceAsStream(RESOURCE)
            ?: error("SmartApp DSL user model snapshot is missing from the plugin classpath: $RESOURCE")
        stream.use { input ->
            val root = InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                JsonParser.parseReader(reader)
            }
            val classes = root.asJsonObject.getAsJsonObject("classes")
                ?: error("SmartApp DSL user model snapshot has no 'classes' object: $RESOURCE")
            val result = LinkedHashMap<String, ClassSnapshot>()
            for ((dotted, value) in classes.entrySet()) {
                val spec = value.asJsonObject
                result[dotted] = ClassSnapshot(
                    fields = spec.getAsJsonArray("fields").mapTo(LinkedHashSet()) { it.asString },
                    attributes = spec.getAsJsonArray("attributes").mapTo(LinkedHashSet()) { it.asString },
                    diagnosticsSafe = spec.get("diagnosticsSafe").asBoolean,
                )
            }
            if (result.isEmpty()) {
                error("SmartApp DSL user model snapshot is empty: $RESOURCE")
            }
            // Класс по умолчанию — пол типового приложения: `USER` там либо не
            // задан вовсе, либо назначает наследника именно его. Непустой
            // снимок без этого класса прошёл бы проверку выше и молча оставил
            // такое приложение без библиотечных имён.
            val default = result[UserModelSpec.defaultClass]
                ?: error(
                    "SmartApp DSL user model snapshot has no default class " +
                        "'${UserModelSpec.defaultClass}': $RESOURCE",
                )
            // Наличия мало: пустой `fields` проверку присутствия проходит, а пол
            // теряет. У промежуточных классов (`Model`) пустой список законен,
            // поэтому проверка адресная.
            if (default.fields.isEmpty()) {
                error(
                    "SmartApp DSL default user class '${UserModelSpec.defaultClass}' " +
                        "declares no fields: $RESOURCE",
                )
            }
            return result
        }
    }
}
