package ru.sber.smartapp.dsl.resources

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.sber.smartapp.dsl.resources.SmartAppUserRootResolver.RootState

/**
 * Корневое имя пользователя по параметризатору: доказательство, его отсутствие
 * и доказательство обратного.
 *
 * Та же таблица входов прогоняется в ядре расширения (`userRoot.test.ts`).
 */
class SmartAppUserRootTest {

    private fun reader(files: Map<String, String>) = object : SmartAppResourceResolver.AppFiles {
        override fun read(relativePath: String): String? = files[relativePath]
        override fun exists(path: String): Boolean =
            files.keys.any { it == path || it.startsWith("$path/") }
    }

    private fun root(files: Map<String, String>) = SmartAppUserRootResolver.of(reader(files))

    /** Тот же класс с доказанной привязкой, но с гасителем: заголовок и хвост свои. */
    private fun withBlocker(header: String, extra: String = ""): Map<String, String> = mapOf(
        "app_config.py" to
            "from app.user.parametrizer import CustomParametrizer\n\nPARAMETRIZER = CustomParametrizer\n",
        "app/user/parametrizer.py" to
            "from scenarios.user.parametrizer import Parametrizer\n\n\n" + header +
            "    def _get_user_data(self, tpr=None):\n" +
            "        data = {}\n        data[\"user\"] = self._user\n        return data\n" + extra,
    )

    /** Типовая раскладка: `PARAMETRIZER = CustomParametrizer` над библиотечным классом. */
    private fun app(body: String): Map<String, String> = mapOf(
        "app_config.py" to
            "from app.user.parametrizer import CustomParametrizer\n\nPARAMETRIZER = CustomParametrizer\n",
        "app/user/parametrizer.py" to
            "from scenarios.user.parametrizer import Parametrizer\n\n\n" +
            "class CustomParametrizer(Parametrizer):\n" +
            "    def _get_user_data(self, tpr=None):\n" +
            body,
    )

    // --- доказанная привязка ---

    @Test
    fun testBindingUnderDefaultNameIsProven() {
        val result = root(
            app(
                "        data = super()._get_user_data(tpr)\n" +
                    "        data[\"user\"] = self._user\n        return data\n",
            ),
        )
        assertEquals(RootState.PROVEN, result.state)
        assertEquals(listOf("user"), result.names)
    }

    @Test
    fun testRootNameNeedNotBeDefault() {
        val result = root(app("        data = {}\n        data[\"me\"] = self._user\n        return data\n"))
        assertEquals(RootState.PROVEN, result.state)
        assertEquals(listOf("me"), result.names)
    }

    @Test
    fun testOneValueUnderTwoKeysGivesTwoRoots() {
        val result = root(
            app(
                "        data = {}\n" +
                    "        data.update({\"user\": self._user, \"account\": self._user})\n" +
                    "        return data\n",
            ),
        )
        assertEquals(listOf("user", "account"), result.names)
    }

    @Test
    fun testLastWriteOfKeyWins() {
        val result = root(
            app(
                "        data = {}\n        data[\"user\"] = other\n" +
                    "        data[\"user\"] = self._user\n        return data\n",
            ),
        )
        assertEquals(RootState.PROVEN, result.state)
    }

    @Test
    fun testLeadingUnderscoreNameCanBeARoot() {
        // Условие «не начинается с `_`» к корневым именам не применяется: ключ
        // словаря параметров приложение выбирает осознанно, и `{{ _u.x }}`
        // в шаблоне законно.
        val result = root(
            app("        data = {}\n        data[\"_u\"] = self._user\n        return data\n"),
        )
        assertEquals(RootState.PROVEN, result.state)
        assertEquals(listOf("_u"), result.names)
    }

    @Test
    fun testFormVariableAloneIsNotARoot() {
        // Под `main_form` уже работает своя семантика (план, раздел 0).
        val result = root(
            app("        data = {}\n        data[\"main_form\"] = self._user\n        return data\n"),
        )
        assertEquals(RootState.DEFAULT, result.state)
        assertEquals(listOf("user"), result.names)
    }

    // --- доказательства нет: работает дефолт ---

    @Test
    fun testNoProofFallsBackToDefaultName() {
        val cases = mapOf(
            "эталонный параметризатор: правило пройдено, ключей ноль" to app(
                "        data = super()._get_user_data(tpr)\n        data.update({})\n        return data\n",
            ),
            "правило нарушено — запись в ветке" to app(
                "        data = {}\n        if flag:\n            data[\"user\"] = self._user\n        return data\n",
            ),
            "значение не распознано" to app(
                "        data = {}\n        data[\"user\"] = build_user()\n        return data\n",
            ),
            "метод не переопределён" to mapOf(
                "app_config.py" to
                    "from app.user.parametrizer import CustomParametrizer\n\nPARAMETRIZER = CustomParametrizer\n",
                "app/user/parametrizer.py" to
                    "from scenarios.user.parametrizer import Parametrizer\n\n\n" +
                    "class CustomParametrizer(Parametrizer):\n    pass\n",
            ),
            "переменной нет" to mapOf("app_config.py" to "RESOURCES = X\n"),
            "переменная присвоена в ветке" to mapOf(
                "app_config.py" to "if dev:\n    PARAMETRIZER = A\nelse:\n    PARAMETRIZER = B\n",
            ),
            "значение не резолвится в класс" to mapOf("app_config.py" to "PARAMETRIZER = build()\n"),
            "цепочка недействительна" to mapOf(
                "app_config.py" to
                    "from app.user.parametrizer import CustomParametrizer\n\nPARAMETRIZER = CustomParametrizer\n",
                "app/user/parametrizer.py" to "class CustomParametrizer(Unknown):\n    pass\n",
            ),
            "назван библиотечный класс" to mapOf(
                "app_config.py" to
                    "from scenarios.user.parametrizer import Parametrizer\n\nPARAMETRIZER = Parametrizer\n",
            ),
            "нет app_config.py" to emptyMap(),
            // Обратиться к такому имени одним сегментом `<имя>.<поле>` нельзя,
            // а ключ словаря такую строку принимает молча.
            "неадресуемое имя ключа: дефис" to app(
                "        data = {}\n        data[\"foo-bar\"] = self._user\n        return data\n",
            ),
            "неадресуемое имя ключа: точка" to app(
                "        data = {}\n        data[\"foo.bar\"] = self._user\n        return data\n",
            ),
            "неадресуемое имя ключа: цифра в начале" to app(
                "        data = {}\n        data[\"9lives\"] = self._user\n        return data\n",
            ),
            "неадресуемое имя ключа: пустая строка" to app(
                "        data = {}\n        data[\"\"] = self._user\n        return data\n",
            ),
            "декорированный метод непроверяем" to mapOf(
                "app_config.py" to
                    "from app.user.parametrizer import CustomParametrizer\n\nPARAMETRIZER = CustomParametrizer\n",
                "app/user/parametrizer.py" to
                    "from scenarios.user.parametrizer import Parametrizer\n\n\n" +
                    "class CustomParametrizer(Parametrizer):\n    @wrap_result\n" +
                    "    def _get_user_data(self, tpr=None):\n" +
                    "        data = {}\n        data[\"user\"] = self._user\n        return data\n",
            ),
            "значение — кортеж из одного элемента" to app(
                "        data = {}\n        data[\"user\"] = self._user,\n        return data\n",
            ),
            // Гаситель в классе параметризатора ломает обе опоры
            // доказательства: что прочитанный `_get_user_data` и есть
            // действующий метод и что `self._user` — атрибут фреймворка.
            "декоратор на классе параметризатора" to
                withBlocker("@replace_class\nclass CustomParametrizer(Parametrizer):\n"),
            "метакласс в заголовке класса" to
                withBlocker("class CustomParametrizer(Parametrizer, metaclass=M):\n"),
            "перехват атрибутов классом" to withBlocker(
                "class CustomParametrizer(Parametrizer):\n",
                "\n    def __getattribute__(self, n):\n        return 1\n",
            ),
            "__init__ без super(): self._user не от фреймворка" to withBlocker(
                "class CustomParametrizer(Parametrizer):\n",
                "\n    def __init__(self, user, items):\n        self._user = None\n",
            ),
            "токен-гаситель в другом методе класса" to withBlocker(
                "class CustomParametrizer(Parametrizer):\n",
                "\n    def helper(self):\n        setattr(self, 'x', 1)\n",
            ),
        )
        for ((title, files) in cases) {
            val result = root(files)
            assertEquals(title, RootState.DEFAULT, result.state)
            assertEquals(title, listOf("user"), result.names)
        }
    }

    // --- доказательство обратного ---

    @Test
    fun testRecognizedForeignValueUnderDefaultNameDisproves() {
        // Предлагать поля модели под именем, про которое прочитано, что в нём
        // другое значение, хуже, чем не предлагать вовсе.
        val result = root(
            app("        data = {}\n        data[\"user\"] = other_user\n        return data\n"),
        )
        assertEquals(RootState.DISPROVED, result.state)
        assertEquals(emptyList<String>(), result.names)
    }

    @Test
    fun testLastWriteDecidesDisprovalToo() {
        val result = root(
            app(
                "        data = {}\n        data[\"user\"] = self._user\n" +
                    "        data[\"user\"] = other_user\n        return data\n",
            ),
        )
        assertEquals(RootState.DISPROVED, result.state)
    }

    // --- свёртка цепочки параметризаторов ---

    private fun twoLevel(baseBody: String, derivedBody: String): Map<String, String> = mapOf(
        "app_config.py" to "from app.user.derived import Derived\n\nPARAMETRIZER = Derived\n",
        "app/user/derived.py" to
            "from app.user.base import Base\n\n\nclass Derived(Base):\n" +
            "    def _get_user_data(self, tpr=None):\n" + derivedBody,
        "app/user/base.py" to
            "from scenarios.user.parametrizer import Parametrizer\n\n\nclass Base(Parametrizer):\n" +
            "    def _get_user_data(self, tpr=None):\n" + baseBody,
    )

    private val bindsUser =
        "        data = {}\n        data[\"user\"] = self._user\n        return data\n"

    @Test
    fun testBaseBindingIsInheritedThroughSuper() {
        val result = root(
            twoLevel(bindsUser, "        data = super()._get_user_data(tpr)\n        return data\n"),
        )
        assertEquals(RootState.PROVEN, result.state)
        assertEquals(listOf("user"), result.names)
    }

    @Test
    fun testBareSuperCallDoesNotInherit() {
        // Результат вызова выброшен: привязок базы в `data` нет, и засчитать их
        // значило бы получить WARNING на корне, которого не существует.
        val result = root(
            twoLevel(
                bindsUser,
                "        data = {}\n        super()._get_user_data(tpr)\n        return data\n",
            ),
        )
        assertEquals(RootState.DEFAULT, result.state)
    }

    @Test
    fun testMethodBuiltFromScratchGetsNoBaseBinding() {
        val result = root(twoLevel(bindsUser, "        data = {}\n        return data\n"))
        assertEquals(RootState.DEFAULT, result.state)
    }

    @Test
    fun testBrokenBaseCancelsInheritedProof() {
        val result = root(
            twoLevel(
                "        data = {}\n        helper(data)\n" +
                    "        data[\"user\"] = self._user\n        return data\n",
                "        data = super()._get_user_data(tpr)\n        return data\n",
            ),
        )
        assertEquals(RootState.DEFAULT, result.state)
    }

    @Test
    fun testBrokenBaseCancelsOwnBindingOfHeirToo() {
        // По плану неподдержанное упоминание в любом методе, участвующем в
        // свёртке, отменяет доказательство целиком. Формально запись наследника
        // легла бы поверх содержимого базы, но WARNING — утверждение, и делать
        // его по методу, про который прочитано «содержимое неизвестно», нельзя.
        val result = root(
            twoLevel(
                "        data = {}\n        helper(data)\n        return data\n",
                "        data = super()._get_user_data(tpr)\n" +
                    "        data[\"user\"] = self._user\n        return data\n",
            ),
        )
        assertEquals(RootState.DEFAULT, result.state)
    }

    @Test
    fun testBrokenBaseThatIsNotInheritedDoesNotMatter() {
        val result = root(twoLevel("        helper(data)\n        return data\n", bindsUser))
        assertEquals(RootState.PROVEN, result.state)
        assertEquals(listOf("user"), result.names)
    }

    @Test
    fun testLastDefinitionOfMethodWins() {
        // Так работает Python: первое определение перекрыто вторым, и словарь
        // собирает именно оно.
        val result = root(
            mapOf(
                "app_config.py" to "from app.user.parametrizer import P\n\nPARAMETRIZER = P\n",
                "app/user/parametrizer.py" to
                    "from scenarios.user.parametrizer import Parametrizer\n\n\nclass P(Parametrizer):\n" +
                    "    def _get_user_data(self, tpr=None):\n        data = {}\n" +
                    "        data[\"first\"] = self._user\n        return data\n\n" +
                    "    def _get_user_data(self, tpr=None):\n        data = {}\n" +
                    "        data[\"second\"] = self._user\n        return data\n",
            ),
        )
        assertEquals(listOf("second"), result.names)
    }

    @Test
    fun testArgumentedSuperDoesNotInherit() {
        val result = root(
            twoLevel(
                bindsUser,
                "        data = super(Base, self)._get_user_data(tpr)\n        return data\n",
            ),
        )
        assertEquals(RootState.DEFAULT, result.state)
    }

    @Test
    fun testBlockerInAnyClassOfChainCancelsProof() {
        // Наследник здесь чистый и связывает `user` сам — без правила «любой
        // гаситель любого класса» каждая строка давала бы `proven`.
        val bases = mapOf(
            "classDecorator" to "@replace_class\nclass Base(Parametrizer):\n    pass\n",
            "metaclassInBases" to "class Base(Parametrizer, metaclass=M):\n    pass\n",
            "__getattribute__" to
                "class Base(Parametrizer):\n    def __getattribute__(self, n):\n        return 1\n",
            "initWithoutSuper" to
                "class Base(Parametrizer):\n    def __init__(self, user, items):\n        self._user = None\n",
            "setattr(" to
                "class Base(Parametrizer):\n    def helper(self):\n        setattr(self, 'x', 1)\n",
        )
        for ((blocker, base) in bases) {
            val result = root(
                mapOf(
                    "app_config.py" to
                        "from app.user.derived import Derived\n\nPARAMETRIZER = Derived\n",
                    "app/user/derived.py" to
                        "from app.user.base import Base\n\n\nclass Derived(Base):\n" +
                        "    def _get_user_data(self, tpr=None):\n" + bindsUser,
                    "app/user/base.py" to
                        "from scenarios.user.parametrizer import Parametrizer\n\n\n" + base,
                ),
            )
            assertEquals(blocker, RootState.DEFAULT, result.state)
        }
    }

    @Test
    fun testBlockerInBaseOfChainCancelsHeirProof() {
        // `__getattribute__` базы перехватывает и метод, и `self._user` у
        // производного экземпляра — чистота самого наследника ничего не значит.
        val result = root(
            mapOf(
                "app_config.py" to "from app.user.derived import Derived\n\nPARAMETRIZER = Derived\n",
                "app/user/derived.py" to
                    "from app.user.base import Base\n\n\nclass Derived(Base):\n" +
                    "    def _get_user_data(self, tpr=None):\n" + bindsUser,
                "app/user/base.py" to
                    "from scenarios.user.parametrizer import Parametrizer\n\n\n" +
                    "class Base(Parametrizer):\n" +
                    "    def __getattribute__(self, n):\n        return 1\n",
            ),
        )
        assertEquals(RootState.DEFAULT, result.state)
    }

    @Test
    fun testClassWithoutOverridePassesStateThrough() {
        val result = root(
            mapOf(
                "app_config.py" to "from app.user.derived import Derived\n\nPARAMETRIZER = Derived\n",
                "app/user/derived.py" to
                    "from app.user.base import Base\n\n\nclass Derived(Base):\n    pass\n",
                "app/user/base.py" to
                    "from scenarios.user.parametrizer import Parametrizer\n\n\n" +
                    "class Base(Parametrizer):\n    def _get_user_data(self, tpr=None):\n" + bindsUser,
            ),
        )
        assertEquals(RootState.PROVEN, result.state)
    }
}
