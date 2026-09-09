package ru.sber.smartapp.dsl.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.sber.smartapp.dsl.resources.SmartAppResourceScanner.DictionaryUse

/**
 * Правило `<d>`: какие формы тела `_get_user_data` дают надёжные привязки, а
 * какие отменяют доказательство целиком.
 *
 * Та же таблица входов прогоняется в ядре расширения (`parametrizerScan.test.ts`).
 * Сравнивается строковый снимок разбора — так расхождение портов видно целиком,
 * а не по одному полю.
 */
class SmartAppParametrizerScanTest {

    private fun render(use: DictionaryUse?): String =
        if (use == null) {
            "правило нарушено"
        } else {
            "<d>=${use.name} super=${use.inheritsBase} [" +
                use.bindings.joinToString(" ") { "${it.key}=${it.value ?: "?"}" } + "]"
        }

    private fun scan(body: String): DictionaryUse? =
        SmartAppResourceScanner.parseModule(
            "class CustomParametrizer(Parametrizer):\n    def _get_user_data(self, tpr=None):\n" + body,
        ).classes[0].methods.firstOrNull { it.name == "_get_user_data" }?.dictionary

    private fun dictionaryOf(body: String): String = render(scan(body))

    @Test
    fun testSupportedForms() {
        val cases = mapOf(
            "эталонный параметризатор приложения" to Pair(
                "        data = super()._get_user_data(tpr)\n        data.update({})\n        return data\n",
                "<d>=data super=true []",
            ),
            "библиотечный стиль: словарь одним литералом" to Pair(
                "        tpr_data = tpr.raw if tpr else {}\n" +
                    "        forms = self._user.forms.collect_form_fields()\n" +
                    "        data = {\n            \"counters\": self._user.counters.raw,\n" +
                    "            \"forms\": forms,\n            \"message\": self._user.message,\n        }\n" +
                    "        return data\n",
                "<d>=data super=false [counters=self._user.counters.raw forms=forms message=self._user.message]",
            ),
            "запись подпиской" to Pair(
                "        data = super()._get_user_data(tpr)\n        data[\"user\"] = self._user\n        return data\n",
                "<d>=data super=true [user=self._user]",
            ),
            "запись через update" to Pair(
                "        data = super()._get_user_data(tpr)\n        data.update({\"user\": self._user})\n        return data\n",
                "<d>=data super=true [user=self._user]",
            ),
            "одно значение под двумя ключами" to Pair(
                "        data = {}\n        data.update({\"user\": self._user, \"account\": self._user})\n        return data\n",
                "<d>=data super=false [user=self._user account=self._user]",
            ),
            "аннотированная инициализация" to Pair(
                "        data: Dict[str, Any] = super()._get_user_data(tpr)\n        data[\"user\"] = self._user\n        return data\n",
                "<d>=data super=true [user=self._user]",
            ),
            "инициализация dict()" to Pair(
                "        data = dict()\n        data[\"user\"] = self._user\n        return data\n",
                "<d>=data super=false [user=self._user]",
            ),
            "инициализация литералом с ключами" to Pair(
                "        data = {\"user\": self._user}\n        return data\n",
                "<d>=data super=false [user=self._user]",
            ),
            "голый вызов super() наследования не даёт" to Pair(
                "        data = {}\n        super()._get_user_data(tpr)\n        data[\"user\"] = self._user\n        return data\n",
                "<d>=data super=false [user=self._user]",
            ),
            "вспомогательные локали правилу не мешают" to Pair(
                "        forms = self._user.forms\n        if forms:\n            log.info(\"x\")\n" +
                    "        data = {}\n        data[\"user\"] = self._user\n        return data\n",
                "<d>=data super=false [user=self._user]",
            ),
            "порядок записей — по смещению, а не по форме" to Pair(
                "        data = {}\n        data.update({\"user\": OTHER}); data[\"user\"] = self._user\n        return data\n",
                "<d>=data super=false [user=OTHER user=self._user]",
            ),
            "значение не распознано" to Pair(
                "        data = {}\n        data[\"user\"] = build_user()\n        return data\n",
                "<d>=data super=false [user=?]",
            ),
            "чужое значение читается как чужое" to Pair(
                "        data = {}\n        data[\"user\"] = other_user\n        return data\n",
                "<d>=data super=false [user=other_user]",
            ),
            "хвостовая запятая делает значение кортежем" to Pair(
                "        data = {}\n        data[\"user\"] = self._user,\n        return data\n",
                "<d>=data super=false [user=?]",
            ),
            "хвостовой комментарий значению не мешает" to Pair(
                "        data = {}\n        data.update({\n            \"user\": self._user  # корень\n        })\n        return data\n",
                "<d>=data super=false [user=self._user]",
            ),
            "строковое значение точечным именем не считается" to Pair(
                "        data = {}\n        data.update({\"user\": \"self._user\"})\n        return data\n",
                "<d>=data super=false [user=?]",
            ),
        )
        for ((title, case) in cases) assertEquals(title, case.second, dictionaryOf(case.first))
    }

    @Test
    fun testFormsThatCancelTheProof() {
        val cases = mapOf(
            "мёртвый код после return" to
                "        data = {}\n        return data\n        data[\"dead\"] = self._user\n",
            "return не последний оператор" to "        data = {}\n        return data\n        x = 1\n",
            "два return" to "        data = {}\n        if x:\n            return data\n        return data\n",
            "return не барного имени" to "        return {\"user\": self._user}\n",
            "нет инициализации" to "        data[\"user\"] = self._user\n        return data\n",
            "вторая инициализация" to "        data = {}\n        data = {}\n        return data\n",
            "запись в ветке if" to
                "        data = {}\n        if enabled:\n            data[\"user\"] = self._user\n        return data\n",
            "запись в однострочном if" to
                "        data = {}\n        if enabled: data[\"user\"] = self._user\n        return data\n",
            "запись в ветке match" to
                "        data = {}\n        match mode:\n            case \"a\":\n                data[\"user\"] = self._user\n        return data\n",
            "алиас словаря" to "        data = {}\n        tmp = data\n        return data\n",
            "точечный префикс имени" to
                "        data = {}\n        other.data[\"user\"] = self._user\n        return data\n",
            "передача словаря в функцию" to "        data = {}\n        helper(data)\n        return data\n",
            // Все упоминания `data` здесь поддержаны — отменяет доказательство
            // именно гаситель: он меняет содержимое в обход имени.
            "гаситель в теле при исправных упоминаниях" to
                "        data = {}\n        data[\"user\"] = self._user\n" +
                "        log.debug(vars(self))\n        return data\n",
            "гаситель в аргументе update" to
                "        data = {}\n        data.update(vars(self))\n        return data\n",
            "распаковка внутри литерала" to
                "        data = {}\n        data.update({\"user\": self._user, **other})\n        return data\n",
            "второй аргумент update" to
                "        data = {}\n        data.update({\"user\": self._user}, user=override)\n        return data\n",
            "аргумент update не литерал" to "        data = {}\n        data.update(other)\n        return data\n",
            // `super(Base, self)` начинает поиск по MRO ПОСЛЕ `Base`, то есть
            // метод самой `Base` не вызывает вовсе. Форму с аргументами не
            // разбираем совсем: инициализация не опознана, значит и правило не
            // выполнено.
            "аргументированный super в инициализации" to
                "        data = super(Base, self)._get_user_data(tpr)\n        return data\n",
            "super с самим классом тоже не принимается" to
                "        data = super(C, self)._get_user_data(tpr)\n        return data\n",
            "вычисляемый ключ в литерале" to
                "        data = {}\n        data.update({key: self._user})\n        return data\n",
        )
        for ((title, body) in cases) assertEquals(title, "правило нарушено", dictionaryOf(body))
    }

    @Test
    fun testSingleLineMethodBodyIsParsed() {
        val module = SmartAppResourceScanner.parseModule(
            "class P(Parametrizer):\n" +
                "    def _get_user_data(self, tpr=None): data = {}; data[\"user\"] = self._user; return data\n",
        )
        assertEquals(
            "<d>=data super=false [user=self._user]",
            render(module.classes[0].methods[0].dictionary),
        )
    }

    @Test
    fun testKeyRangePointsAtLiteralContent() {
        val source = "class P(Parametrizer):\n    def _get_user_data(self, tpr=None):\n" +
            "        data = {}\n        data[\"user\"] = self._user\n        return data\n"
        val binding = SmartAppResourceScanner.parseModule(source)
            .classes[0].methods[0].dictionary!!.bindings[0]
        assertEquals("user", source.substring(binding.nameStart, binding.nameEnd))
    }

    @Test
    fun testDecoratedMethodIsNotParsed() {
        // Декоратор подменяет результат целиком: тело собирает одно, а в шаблон
        // уедет то, что вернул декоратор.
        for (decorator in listOf("    @staticmethod\n", "    @wrap_result\n")) {
            val module = SmartAppResourceScanner.parseModule(
                "class P(Parametrizer):\n" + decorator +
                    "    def _get_user_data(self, tpr=None):\n" +
                    "        data = {}\n        data[\"user\"] = self._user\n        return data\n",
            )
            assertNull(decorator, module.classes[0].methods[0].dictionary)
        }
    }

    @Test
    fun testOtherMethodIsNotParsed() {
        val module = SmartAppResourceScanner.parseModule(
            "class P(Parametrizer):\n    def collect(self, tpr=None):\n" +
                "        data = {}\n        data[\"user\"] = self._user\n        return data\n",
        )
        assertNull(module.classes[0].methods[0].dictionary)
    }
}
