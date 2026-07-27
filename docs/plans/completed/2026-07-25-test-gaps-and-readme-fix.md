# Закрытие пробелов в тестах (Find Usages, Rename, невалидный JSON, подсветка структурных ключей) и актуализация README

> **Статус:** реализован (план перенесён в `completed/`).
>
> **Отклонения от плана при реализации** (для истории):
> - При ревью обнаружилось, что guard `PsiErrorElement` сам по себе не применится к
>   уже сохранённым persistent-данным: версии обоих индексов (`SmartAppDefinitionIndex`,
>   `SmartAppNameIndex`) повышены через `CONTENT_VERSION = 1` (см. P1 ревью).
> - guard перенесён с корневого `JsonObject` на **весь `JsonFile`** (API сужено с
>   `PsiElement` до `JsonFile`, чтобы повторная передача корневого объекта не
>   отключила trailing-проверку) и расширен: один `PsiErrorElement` не ловит
>   trailing-garbage после `}` (парсер молча игнорирует suffix без PSI-ошибки),
>   поэтому `JsonPsi.hasError` комбинирует проверку `PsiErrorElement` и
>   `hasTrailingContent` (значимый контент после корневого значения).
>   Regression-тест: `testTrailingGarbageAfterObjectIsRejected`
>   (`{ "phantom_form": { "type": "form" } } unexpected`).
> - H5 оформлен **статическим контрактом** (`testAnnotatorIsDumbAwareStaticContract`),
>   а не runtime-проверкой: `myFixture.doHighlighting()` виснет в
>   `runInDumbModeSynchronously` (см.
>   `docs/insights/2026-07-25-dohighlighting-hangs-in-dumb-mode.md`).
> - F4 расширен фикстурами и проверками для **всех** `SmartAppRefKind` (ACTION,
>   BEHAVIOR, FILLER, CLASSIFIER), не только FORM/SCENARIO.
> - J2–J5 усилены сравнением **полного списка имён** с baseline из setUp
>   (`assertFormsEqualBaseline`), а не поиском произвольного имени.
> - H6 переписан на валидный JSON и точное сопоставление highlight с диапазоном
>   конкретного literal (`form_filling`, `question`, `approve`).

## Context

v1 плагина реализована и покрыта **28 тестами**: 24 интеграционных в
`SmartAppDslTest.kt` (resolve всех веток правил `SmartAppRefRules`, multi-resolve,
дубликаты top-level ключей, completion значений `type` и ссылочных имён, dumb mode,
Jinja-guard, scope-изоляция, unresolved-WARNING, keyword-highlight) и 4 unit-теста в
`SmartAppKeywordsTest.kt` (загрузчик словаря).

При анализе покрытия обнаружены **четыре области**, которые либо не покрыты тестами
вовсе, либо покрыты частично:

1. **Find Usages** — `SmartAppFindUsagesProvider` и `SmartAppElementDescriptionProvider`
   проверяются только косвенно (через зарегистрированный reference contributor).
2. **Rename** — `SmartAppReference.handleElementRename` делегирует базовому классу и
   не покрыт; rename top-level определения с обновлением ссылок не верифицирован.
3. **Невалидный JSON / частичный PSI в индексах** — поведение `SmartAppDefinitionIndex`
   и `SmartAppNameIndex` на битом JSON, не-объекте на верхнем уровне, пустом файле
   не протестировано; при этом текущая реализация **содержит реальный дефект**
   (см. ниже), который без правки не пройдёт новые тесты.
4. **Structural-key highlighting** — `SmartAppTextAttributes.FIELD` на структурных
   ключах не верифицирован (существующий тест ловит только `KEYWORD` на значении
   `type`).

Отдельно: `README.md` содержит **два устаревших** относительно фактической сборки
утверждения — JVM target 17 (фактически 21) и `sinceBuild` 233 (фактически 251).
Эти расхождения введут в заблуждение пользователей и авторов будущих планов.

**Scope:** тесты (новые тестовые классы) + правки `README.md` + **одна вынужденная
продуктовая правка** (guard `PsiErrorElement` в индексах, п.3). Если иной тест
выявит реальный дефект — заводится `docs/insights/2026-07-25-…-bug.md` и принимается
решение править/принять.

**Согласованный объём:** все ~32 candidate-теста, включая negative-case, round-trip
externalizer'ов и dumb-mode для аннотатора.

## Архитектура тестов

Тесты наследуют `BasePlatformTestCase` (как `SmartAppDslTest`) и создают фикстуры
через `myFixture.addFileToProject("static/references/<kind>/…", …)` — иначе не
сработает `SmartAppFiles.kindOf` (требует подряд идущих сегментов
`static`/`references`/`<kind>`). Чистые unit-тесты externalizer'ов (без платформы)
выполняются в стиле `SmartAppKeywordsTest` (plain `org.junit`) и лежат в подкаталоге
`index/`.

Вспомогательные методы из `SmartAppDslTest` (`findLiteral`, `smartAppReference`,
`addScenario`) повторяются в новых классах через копирование, а не через общий
базовый класс (как уже сделано в существующем коде).

### Тестовые файлы для создания

| Файл | Базовый класс | Тестов |
|---|---|---|
| `SmartAppFindUsagesTest.kt` | `BasePlatformTestCase` | 11 (F1–F11) |
| `SmartAppRenameTest.kt` | `BasePlatformTestCase` | 4 (R1–R4) |
| `SmartAppIndexTest.kt` | `BasePlatformTestCase` | 8 (J1–J6, J11, regression trailing) |
| `SmartAppHighlightingTest.kt` | `BasePlatformTestCase` | 6 (H1–H6) |
| `index/SmartAppDefinitionExternalizerTest.kt` | plain JUnit | 4 (J7–J10) |

### 0. Исходная статистика покрытия (фиксация для отчётности)

- `SmartAppDslTest.kt`: 24 `@Test`.
- `SmartAppKeywordsTest.kt`: 4 `@Test`.
- **Итого 28.** После плана: 28 + 33 = 61 (добавлен regression-тест на
  trailing-garbage после корневого объекта).

## Общая продуктовая правка — guard битого JSON в индексах

**Платформенный контракт (важно).** JSON PSI при синтаксической ошибке обычно
строит **частичное дерево с `PsiErrorElement`**, а **не** бросает исключение.
Текущие индексы (`SmartAppDefinitionIndex.map`, `SmartAppNameIndex.map`) имеют
`try/catch → emptyMap()`, но этот `catch` фактически не срабатывает для типичного
битого JSON — вместо исключения парсер доbuildит частичный `JsonObject`, и индекс
может проиндексировать **обрывочный top-level ключ** (например, `a` из
`{ "a":`). Дополнительный подводный камень, вскрытый на ревью: trailing-garbage
после `}` (например `{ "a": {} } unexpected`) парсер **молча игнорирует** — он
достраивает корневой объект, а suffix оставляет неприсоединённым, **без**
`PsiErrorElement` (подтверждено diagnostic-тестом: `fileHasError == false`).
Поэтому одного PsiErrorElement-guard'а недостаточно: валидный ключ из такого
файла всё равно попадал бы в индекс.

**Правка (`fix:`-коммит).** Общий хелпер `JsonPsi.hasError(file: JsonFile)`
комбинирует две независимые проверки:

```kotlin
object JsonPsi {
    // API сознательно принимает JsonFile, а не PsiElement: trailing-проверка
    // осмысленна только для файла целиком, и передача сюда корневого объекта
    // снова молча отключила бы её, вернув исходную брешь.
    fun hasError(file: JsonFile): Boolean = hasPsiError(file) || hasTrailingContent(file)

    private fun hasPsiError(file: JsonFile): Boolean =
        PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) != null

    // Мусор после корневого значения, который парсер молча игнорирует без PsiErrorElement.
    private fun hasTrailingContent(file: JsonFile): Boolean {
        val root = file.topLevelValue ?: return false
        val text = file.text
        for (i in root.textRange.endOffset until text.length) {
            if (!text[i].isWhitespace()) return true
        }
        return false
    }
}
```

Вызывается в обоих индексах после safe-cast `inputData.psiFile as? JsonFile`
(весь файл, не только корневой объект) и **до** извлечения `topLevelValue`: при
`hasError(jsonFile)` индексер возвращает `emptyMap()`. `try/catch` сохраняется как
страховка. **Версии индексов повышены** (P1 ревью): без этого persistent-индекс
сохранил бы обрывочные ключи из уже проиндексированных битых файлов до их
повторного изменения — новый guard к ним не применится. Введена константа
`CONTENT_VERSION = 1` (отдельно от `SERIALIZATION_VERSION` — формата сериализации),
входящая в `getVersion()` обоих индексов:
`SmartAppDefinitionIndex.getVersion() = 1 + SERIALIZATION_VERSION + CONTENT_VERSION`,
`SmartAppNameIndex.getVersion() = 1 + CONTENT_VERSION`.

Хелпер `JsonPsi.hasError` выносится в общий объект **намеренно** — он будет
переиспользован в План 2 (`SmartAppFormFieldIndex`).

## 1. Find Usages — `SmartAppFindUsagesTest.kt`

Проверяемые компоненты: `SmartAppFindUsagesProvider` (`canFindUsagesFor`, `getType`,
`getDescriptiveName`, `getNodeText`, `getWordsScanner`, `isDslDefinition`, `kindOf`)
и `SmartAppElementDescriptionProvider` (`getElementDescription` с ключами
`UsageViewTypeLocation` / `UsageViewLongNameLocation`).

**Unit-тесты на провайдерах** (10) — быстро, без UI. Top-level `JsonProperty`
получаем через `PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java)` +
фильтр `it.parent.parent is JsonFile`:

- **F1** `testFindUsagesProviderCanFindUsagesForTopLevelDefinition` — на top-level
  свойстве формы `hello_form`: `canFindUsagesFor == true`.
- **F2** `testFindUsagesProviderRejectsNestedProperty` — на вложенном свойстве
  `fields.name` (из фикстуры `hello_form`): `canFindUsagesFor == false`.
- **F3** `testFindUsagesProviderRejectsNonDslFile` — на `JsonProperty` из файла вне
  `static/references/…`: `canFindUsagesFor == false`.
- **F4** `testGetTypeReturnsLowercasedKind` — для каждого `SmartAppRefKind`,
  представленного в setUp-фикстурах: `getType(prop) == kind.name.lowercase()`.
- **F5** `testGetDescriptiveNameAndNodeTextReturnPropertyName` — на `hello_form`:
  `getDescriptiveName == "hello_form"`, `getNodeText(_, false) == "hello_form"`.
- **F6** `testGetTypeFallsBackToEntityForNonDsl` — на non-DSL `JsonProperty`:
  `getType == "entity"` (ветка `?: "entity"`).
- **F7** `testElementDescriptionTypeLocation` —
  `getElementDescription(prop, UsageViewTypeLocation.INSTANCE) == "form"`.
- **F8** `testElementDescriptionLongNameLocation` —
  `getElementDescription(prop, UsageViewLongNameLocation.INSTANCE) == "hello_form"`.
- **F9** `testElementDescriptionReturnsNullForNonDsl` — на non-DSL property и
  на не-`JsonProperty`: `null`.
- **F10** `testElementDescriptionReturnsNullForUnknownLocation` — на произвольной
  `ElementDescriptionLocation` (анонимный объект): `null` (ветка `else`).

**Интеграционный тест** (1):

- **F11** `testFindUsagesIntegrationFindsReferenceInScenario` — добавить в setUp
  сценарий с `"form": "hello_form"`, вызвать `myFixture.findUsages(helloFormProp)`,
  ассерт: в результирующем `Set<UsageInfo>` есть usage на литерале `"hello_form"` в
  файле сценария.

## 2. Rename — `SmartAppRenameTest.kt`

Проверяемый компонент: `SmartAppReference.handleElementRename` (делегирует базовому
`PsiPolyVariantReferenceBase`) и поток переименования top-level определения.

- **R1** `testRenameDefinitionUpdatesReferences` — добавить в setUp сценарий с
  `"form": "hello_form"`, вызвать `myFixture.renameElement(helloFormProp,
  "renamed_form")`. Ассерты: ключ в `forms.json` → `renamed_form`; литерал в
  сценарии → `renamed_form`; `multiResolve` на новой ссылке находит переименованный
  `JsonProperty`.
- **R2** `testRenameReferenceRewritesStringOnly` — caret на литерале `"hello_form"`
  в сценарии; `ref.handleElementRename("renamed")`. Ассерт: строка изменилась,
  **но** определение `hello_form` в `forms.json` осталось прежним (reference-only
  rename).
- **R3** `testHandleElementRenameDelegatesToSuper` — прямой unit-вызов
  `SmartAppReference(literal).handleElementRename("new_name")`, ассерт
  `element.value == "new_name"` (spec-тест, фиксирующий поведение).
- **R4** `testRenameTopLevelScenarioUpdatesRunScenarioRefs` — на top-level
  `other_scenario`: `renameElement(..., "renamed_scn")`. Используется фикстура
  сценария, ссылающегося через `"scenario": "other_scenario"` (по образцу
  `testResolveRunScenario`). Ассерт: ссылка обновилась.

**Риск R1/R4.** In-place rename в `BasePlatformTestCase` для `JsonProperty` может
иметь нюансы (in-place vs. dialog-mode). Если `myFixture.renameElement` не обновит
ссылки автоматически — это сигнал, что reference contributor не обеспечивает
«definition → references» flow; тогда заводится insight и отдельно решается
(возможно, требуется `RenamePsiElementProcessor`). Тест фиксирует **текущее
ожидаемое** поведение; при его отсутствии план приостанавливается для правки.

## 3. Невалидный JSON / индексы — `SmartAppIndexTest.kt`

Платформенные edge-case'ы (7). Каждый файл добавляется через
`myFixture.addFileToProject("static/references/forms/<name>.json", content)`;
после добавления проверяется **обоими** способами: (а) битый файл сам не даёт
определений/имён (`findDefinitions`/`allNames` не содержат обрывочного ключа),
(б) валидный файл рядом остаётся корректным:

- **J1** `testDefinitionIndexSkipsBrokenJson` — `{ "a":` (обрыв): ассерт, что
  `findDefinitions(project, "a", [FORM])` пусто **И** `allNames(project, FORM)` не
  содержит `"a"`. **Это тест правки из раздела «Общая продуктовая правка»**, а не
  ветки `catch`.
- **J2** `testDefinitionIndexSkipsArrayTopLevel` — `[1, 2, 3]` (верхний уровень —
  массив).
- **J3** `testDefinitionIndexSkipsStringTopLevel` — `"hi"` (строка как корень).
- **J4** `testDefinitionIndexSkipsEmptyFile` — пустое содержимое.
- **J5** `testDefinitionIndexEmptyJsonObjectProducesNoNames` — `{}`.
- **J6** `testBrokenFileDoesNotCorruptValidFiles` — в одном каталоге `forms.json`
  (валидный, `hello_form`) + `broken.json` (битый); ассерт, что `findDefinitions`
  для `hello_form` по-прежнему работает (изоляция сбоя).
- **J11** `testNameIndexRoundTripViaPlatform` — косвенный round-trip приватного
  `NamesExternalizer`: файл с несколькими top-level ключами, `allNames(project,
  FORM)` возвращает их в исходном порядке (покрывает `save`+`read`).

## 4. Round-trip externalizer'а — `index/SmartAppDefinitionExternalizerTest.kt`

Plain JUnit (стиль `SmartAppKeywordsTest`), без платформы. Паттерн round-trip через
`ByteArrayOutputStream` → `DataOutputStream` → `ByteArrayInputStream` →
`DataInputStream`:

- **J7** `testExternalizerRoundTripEmpty` — `SmartAppDefinitionValue(emptyList())`.
- **J8** `testExternalizerRoundTripSingleOffset` — `SmartAppDefinitionValue(listOf(42))`.
- **J9** `testExternalizerRoundTripMultipleOffsets` —
  `SmartAppDefinitionValue(listOf(0, 128, 65536))` (проверка var-int кодировки
  `DataInputOutputUtil.writeINT` для значений > 127).
- **J10** `testExternalizerRoundTripPreservesOrder` —
  `SmartAppDefinitionValue(listOf(10, 5, 20))`, порядок сохраняется.

`SmartAppNameIndex.NamesExternalizer` — `private object`, прямой unit-тест
невозможен; покрывается косвенно через J11.

## 5. Structural-key highlighting — `SmartAppHighlightingTest.kt`

Проверяемый компонент: `SmartAppAnnotator.annotateStructuralKey` (FIELD на
`property.nameElement.textRange`) и полный набор `STRUCTURAL_KEYS`.

- **H1** `testStructuralKeyFormIsHighlightedAsField` — на сценарии из setUp: есть
  `HighlightInfo` с `forcedTextAttributesKey == SmartAppTextAttributes.FIELD`,
  покрывающий `property.nameElement.textRange` ключа `"form"`.
- **H2** `testAllStructuralKeysHighlighted` — конструируется файл со всеми ключами
  из `STRUCTURAL_KEYS` (`form`, `filler`, `classifier`, `action`, `behavior`,
  `scenario`, `scenario_description`, `actions`, `requirement`, `fields`,
  `questions`, `on_filled_actions`); для каждого — FIELD highlight на `nameElement`.
- **H3** `testNonStructuralKeyNotHighlightedAsField` — на произвольном ключе
  (`"comment"`): **нет** FIELD highlight на его `nameElement` (negative-case).
- **H4** `testStructuralKeyHighlightRangeCoversOnlyNameElement` — для
  `"on_filled_actions"` диапазон `HighlightInfo` совпадает с
  `prop.nameElement.textRange`, не покрывает значение/двоеточие.
- **H5** `testAnnotatorIsDumbAwareStaticContract` — **статический контракт, не
  функциональный тест dumb mode.** Прямой прогон `myFixture.doHighlighting()` внутри
  `runInDumbModeSynchronously` виснет (см.
  `docs/insights/2026-07-25-dohighlighting-hangs-in-dumb-mode.md`), поэтому H5
  проверяет только маркер `DumbAware` на `SmartAppAnnotator`: пока он стоит,
  платформа вызывает аннотатор и в dumb mode, а чисто-PSI ветка структурных ключей
  не зависит от индекса. Функциональная корректность FIELD покрыта H1–H4. Известное
  ограничение: контракт НЕ ловит регрессию вида «индексное чтение добавили перед
  structural-веткой» — для этого потребовался бы отдельный test seam (прямой вызов
  `annotate(...)` на hand-made `AnnotationHolder` без daemon-pass); в рамках плана
  признано избыточным.
- **H6** `testKeywordHighlightUsesContextCategory` — на `type: "form_filling"` в
  сценарии → KEYWORD **точно на диапазоне literal**; на `type: "question"` внутри
  `fields` → KEYWORD на диапазоне literal (категория `field_description`);
  `type: "approve"` в позиции сценария **не** подсвечивается. Фикстуры — валидный
  JSON (без висячих запятых); сопоставление идёт по `(forcedTextAttributesKey,
  startOffset, endOffset)` конкретного literal, а не по «любому KEYWORD».

**Механика.** `myFixture.doHighlighting()` → `MutableList<HighlightInfo>`;
проверка `infos.any { it.forcedTextAttributesKey == … }`, для range — сравнение
`info.startOffset`/`endOffset` с `property.nameElement.textRange`.

## 6. Актуализация README

Только **правки текста** `README.md`, без продуктового кода. Выполненный план
`docs/plans/completed/2026-05-30-…md` **не трогается** (историческая запись).

| README (строка) | Было | Стало |
|---|---|---|
| 42 | `build **233** (IDEA 2023.3) и новее` | `build **251** (IDEA 2025.1) и новее` |
| 71 | `Kotlin 2.0.21 (JVM target 17)` | `Kotlin 2.0.21 (JVM target 21)` |

Остальные цифры в README согласуются с фактической сборкой и **не меняются**:
IntelliJ Platform Gradle Plugin 2.16.0, Gradle 9.0.0 (подтверждено
`gradle/wrapper/gradle-wrapper.properties`), JBR 21, gson 2.11.0, JUnit 4.

## Проверка

1. **Компиляция тестов:** `./gradlew compileTestKotlin` (с
   `export JAVA_HOME="/Applications/GIGA IDE CE 2025.1.app/Contents/jbr/Contents/Home"`).
2. **Полный прогон:** `./gradlew test` — все 32 новых теста зелёные, существующие
   28 не сломаны.
3. **Правка индексов (раздел «Общая продуктовая правка»)** — обязательна для J1;
   без неё J1 падает (тест корректно ловит дефект).
4. **Правка README** проверяется ручным чтением; `./gradlew buildPlugin` остаётся
   зелёным (README на сборку не влияет — это sanity-check, что ничего лишнего не
   задето).

## Замечания

- **Без новых зависимостей.** Все нужные импорты (`UsageViewTypeLocation`,
  `HighlightInfo`, `DumbModeTestUtils`, `ByteArrayOutputStream`, `PsiErrorElement`)
  уже доступны в classpath платформы/JDK.
- **Стиль тестов:** имена методов — `test<Существительное><Поведение>` (как в
  существующем `SmartAppDslTest`); комментарии — на русском; сообщения в
  `assertTrue(..., "...")` — на русском.
- **Порядок коммитов** (один логический шаг — один коммит):
  1. `fix: пропускать файлы с PsiErrorElement в индексах (общий хелпер JsonPsi)`
  2. `test: добавить тесты Find Usages (провайдеры + интеграция)`
  3. `test: добавить тесты rename (definition→refs, handleElementRename)`
  4. `test: добавить тесты индексов на невалидный JSON и edge-case'ы`
  5. `test: добавить round-trip тесты SmartAppDefinitionExternalizer`
  6. `test: добавить тесты подсветки структурных ключей`
  7. `docs: актуализировать README (JVM 21, sinceBuild 251)`
- Каждый `fix:`-коммит правки продуктового кода вставляется **после**
  соответствующего `test:`-коммита, выявившего дефект (если помимо запланированной
  правки индексов обнаружится иной бег).

## Future work (вне этого плана)

- Покрытие негативных веток `SmartAppRefRules` (`save_behavior`, `classifier_meta`,
  `form` в не-SCENARIO файле, `filler` без `external`, и т.д.).
- Round-trip `SmartAppScopes` fallback (`referencesRoot == null → projectScope`).
- Edge-case'ы `SmartAppFiles` (`kindOf(directory)`, не-`.json` расширение,
  `static/references/<unknown-dir>/x.json`).
- Тест регистрации всех extensions в `plugin.xml` (что классы резолвятся).
