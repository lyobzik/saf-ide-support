# Поддержка полей форм и Jinja-навигации/подсветки

## Context

Поля форм живут **внутри** top-level определения формы: структура
`forms.<form>.fields.<field>`, где `<field>` — `JsonProperty` второго уровня. Эти
имена **не индексируются** `SmartAppDefinitionIndex`: он обходит только
`root.propertyList` (top-level ключи) и валидирует `isTopLevelDefinition` через
`owner.parent is JsonFile`. Значения в JSON-файлах содержат Jinja2-шаблоны вида
`{{ main_form.<field> }}`.

Сейчас **любое** строковое значение, содержащее `{{` или `{%`, полностью
пропускается (`SmartAppReferenceContributor.isJinja` → ранний `return EMPTY`;
дублирующая проверка в `SmartAppAnnotator.kt:87`). Такие значения не резолвятся и
не подсвечиваются как неразрешённые — это сознательная политика v1, чтобы динамические
шаблоны не выглядели «битой ссылкой».

**Цель:** разрешать `{{ main_form.<field> }}` в определения полей формы, дать
автодополнение имён полей и полноценную Jinja-разметку.

**Согласованный scope:**
- **Проектировать под `main_form.<field>`** — плоский доступ к полям формы;
  остальной Jinja-синтаксис (фильтры, циклы, `variables.*`, подобъекты
  `main_form.x.y`) **не парсить и не подсвечивать как ошибку**. В репозитории нет
  боевых образцов Jinja (поиском `grep` по `*.json` подтверждено только
  `{{ main_form.name }}` / `{{ main_form.x }}` в тестах/комментариях) — ограничение
  зафиксировать явно; расширение — отдельная итерация после получения реальных
  файлов.
- **Полная Jinja-разметка** — раздельные цвета для разделителей, переменных,
  оператора `.`, фильтров, строковых литералов внутри шаблона (5 атрибутов).

**Ключевые архитектурные решения:**
- **FIELD — отдельное понятие, НЕ расширение `SmartAppRefKind`.** Enum жёстко связан
  с `dirName` (каталогом); у полей каталога нет. Расширение сломало бы
  `SmartAppFiles.kindFromSegments`, `SmartAppTypeContext.fileKindCategory`,
  `SmartAppDefinitionIndex.isTopLevelDefinition`. FIELD моделируется своим типом
  `FormFieldRef`.
- **Типизированный ключ индекса `FormFieldRef` через `KeyDescriptor`** (а не строка
  с `:`) — JSON-имена могут содержать `:`, и пары form/field коллидировали бы.
- **`SmartAppFieldReference` — `PsiPolyVariantReferenceBase`**, диапазон только на
  имя поля (не на весь `main_form.name`) — для корректных Go to Definition,
  Find Usages и будущего rename поля.
- **Диапазоны по raw-тексту literal**, не по декодированному `value`.
- **Семантическая ссылка только внутри корректно закрытого `{{ … }}`**, вне
  строковых литералов; `{% … %}` — только лексически подсвечивается.
- **Completion — ранняя Jinja-ветка**, завершающая обработку.
- **Общий предикат «поле формы»** в обоих find-usages провайдерах.

**Зависимость от Плана 1:** переиспользуется общий хелпер `JsonPsi.hasError` для
защиты нового индекса от частичного/битого JSON. Поэтому реализация начинается
**после** Плана 1.

```
src/main/kotlin/ru/sber/smartapp/dsl/
  reference/SmartAppFieldRef.kt           # FormFieldRef + targetFormOf + isFieldDefinition
  reference/SmartAppJinjaLexer.kt         # детерминированный лексер Jinja-фрагментов
  reference/SmartAppJinjaOffsets.kt       # raw→absolute смещения внутри literal
  reference/SmartAppFieldReference.kt     # поли-вариантная ссылка на имя поля
  index/SmartAppFormFieldIndex.kt         # FileBasedIndexExtension<FormFieldRef, ...Value>
  index/SmartAppFormFieldNameIndex.kt     # FileBasedIndexExtension<String, List<String>>
```

## 1. Модель FIELD и определение формы — `reference/SmartAppFieldRef.kt`

- **`data class FormFieldRef(val form: String, val field: String)`** — типизированный
  ключ поля. Не добавляется в `SmartAppRefKind`.
- **`object FormFieldRefDescriptor : KeyDescriptor<FormFieldRef>`** —
  `save`/`read`/`getHashCode`/`isEqualTo`. Сериализация — две length-prefixed
  UTF-строки (через `IOUtil.writeUTF`/`readUTF`), что однозначно и не даёт коллизий
  на `:` в именах.
- **`fun targetFormOf(literal: JsonStringLiteral): String?`** — алгоритм
  определения целевой формы (важно: **не** поиск sibling `form` у непосредственного
  владельца):
  1. Подняться от literal до **top-level свойства сценария** (обход `ancestor` до
     `JsonProperty`, у которого `parent.parent is JsonFile`).
  2. Убедиться, что файл — SCENARIO (`SmartAppFiles.kindOf == SCENARIO`); иначе
     `null`.
  3. Взять свойство `form` этого top-level объекта.
  4. Вернуть его значение **только если**: это статический `JsonStringLiteral`,
     непустое, и **не содержит Jinja** (`!SmartAppReferenceContributor.isJinja(...)`).
  5. Иначе `null`.
  Это сохраняет существующий динамический кейс `"form": "{{ main_form.name }}"`
  без ссылки и без ложного WARNING (форма неизвестна → `targetFormOf == null` →
  поле не резолвится, но и не ругается).
- **`fun isFieldDefinition(property: JsonProperty): Boolean`** — общий предикат
  (используется в find-usages): `property.parent` — `JsonObject`, чей `parent` —
  `JsonProperty` с именем `"fields"`, чей владелец — top-level `JsonProperty` в
  FORM-файле.

## 2. Индексы полей — `index/SmartAppFormFieldIndex.kt` + `index/SmartAppFormFieldNameIndex.kt`

### `SmartAppFormFieldIndex : FileBasedIndexExtension<FormFieldRef, SmartAppDefinitionValue>`

- **Ключ — типизированный `FormFieldRef`** через `FormFieldRefDescriptor` (см. п.1).
- **Value — переиспользуется `SmartAppDefinitionValue(offsets: List<Int>)` +
  `SmartAppDefinitionExternalizer`** (формат тот же — var-int offsets).
- **Indexer:** для **каждой** top-level формы из `root.propertyList` →
  `formProp.value as? JsonObject` → `findChild` свойства `"fields"` как `JsonObject`
  → обход **`fields.propertyList`** (сохраняет дубли полей) →
  `emit(FormFieldRef(form=formProp.name, field=child.name),
  child.nameElement.textRange.startOffset)`. Дубли полей в одной форме попадают в
  один `offsets`-список (по образцу `SmartAppDefinitionIndex`).
- **Защита от частичного/битого JSON:** guard `JsonPsi.hasError(root)` (из Плана 1)
  сразу после получения корневого `JsonObject` → `emptyMap()`. `try/catch →
  emptyMap()` сохраняется как страховка.
- **`getVersion()` = `1 + SmartAppDefinitionExternalizer.SERIALIZATION_VERSION`**
  (не константа; по образцу `SmartAppDefinitionIndex:40`). При смене externalizer'а
  версия поднимется автоматически.
- **`getInputFilter()`** — FORM-файлы (`SmartAppFiles.kindOf == FORM`).
- **`findFields(project, form, field, scope): List<JsonProperty>`** — точка входа
  для резолва, под `DumbService.isDumb` guard у вызывающего.

### `SmartAppFormFieldNameIndex : FileBasedIndexExtension<String, List<String>>`

(для автодополнения имён полей по аналогии с `SmartAppNameIndex`)

- **Ключ — escape-кодированное имя формы** через `EnumeratorStringDescriptor`
  (однозначное, без коллизий с `:`).
- **Значение — дедуплицированный список имён полей** формы (через `LinkedHashSet` →
  `toList`, порядок сохранения первого вхождения).
- **Свой `DataExternalizer<List<String>>`** (по образцу приватного
  `SmartAppNameIndex.NamesExternalizer`: var-int count + `IOUtil.writeUTF`).
- **`getVersion()` = 1** (своя serialization version, не заимствованная).
- **`allNames(project, form, scope): List<String>`** — точка входа для completion.
- **`getInputFilter()`** — FORM-файлы; guard `JsonPsi.hasError` — идентично.

### Регистрация

В `plugin.xml` добавляются **два** новых `<fileBasedIndex implementation-class=…/>`
(по образцу существующих `SmartAppDefinitionIndex` / `SmartAppNameIndex`).

## 3. Лексический контракт Jinja — `reference/SmartAppJinjaLexer.kt`

**Не полноценный Jinja-парсер, а детерминированный лексер.** На входе — raw-текст
literal (без JSON-кавычек, см. п.5), на выходе — упорядоченный список токенов с
`(startOffset, endOffset)` относительно raw-текста.

**Контракт:**
- **Парные разделители учитываются семантически.** Сканирование raw-текста слева
  направо: `{{ … }}` и `{% … %}` — парные; **непарные** (нет закрывающего `}}`/`%}`
  до конца literal) — весь остаток помечается одним `TEXT`-токеном, **ошибкой/ссылкой
  не становится**.
- **Несколько фрагментов в одном literal** обрабатываются независимо
  (например `"x={{ a }} y={{ b }}"` → два выражения + `TEXT` между).
- **Внутри `{{ … }}` токены** (приоритет, жадность слева направо):
  1. `VAR` — `\b(?:main_form)\b` либо `\b[A-Za-z_][A-Za-z0-9_]*\b` после
     `{{`/`.`/`|`/оператора → идентификатор. **Семантическая ссылка создаётся
     только для `main_form.<id>`** (см. п.4); прочие идентификаторы → только цвет
     `VAR`, без резолва/WARNING.
  2. `DOT` — одиночный `.`.
  3. `FILTER_OP` — `|`; `FILTER_NAME` — идентификатор сразу после `|`.
  4. `STRING` — `"…"` или `'…'` с учётом escape (`\"`, `\\`, …). **Внутри строки
     идентификаторы НЕ токенизируются** (п.8: `"main_form.name"` → один `STRING`,
     не `VAR`).
  5. `DELIM` — сами `{{`/`}}`/`{%`/`%}`.
  6. `TEXT` — всё прочее (внутри `{{ }}` и снаружи).
- **`{% … %}`** — **только лексическая подсветка** (`DELIM` + внутренние
  `STRING`/`TEXT`); **семантических ссылок и WARNING не создаётся** (п.6/8).
- **Что считать переменной/фильтром** явно определено выше — это устраняет
  неоднозначность перекраски частей строк.

## 4. Reference для подстрок Jinja — `reference/SmartAppFieldReference.kt` + правка `SmartAppReferenceContributor.kt`

- **`SmartAppFieldReference : PsiPolyVariantReferenceBase<JsonStringLiteral>`**
  (поли-вариантная, по образцу `SmartAppReference`), конструктор
  `(literal, fieldRangeInLiteral: TextRange, ref: FormFieldRef)`.
  - `rangeRelativeToBase` = `fieldRangeInLiteral` — **диапазон только на имя поля**
    (не на весь `main_form.name`). Это даёт корректные Go to Definition, Find Usages,
    будущий rename поля.
  - `multiResolve` через `SmartAppFormFieldIndex.findFields(project, ref.form,
    ref.field, SmartAppScopes.forElement(element))` под `DumbService.isDumb` guard;
    возвращает все `JsonProperty` полей (дубли → `size == 2`).
- **Правка `SmartAppReferenceContributor.getReferencesByElement`:** вместо раннего
  `if (isJinja(value)) return EMPTY`:
  1. Распарсить literal через `SmartAppJinjaLexer` (по **raw-тексту**, см. п.5).
  2. Найти все последовательности `VAR(main_form) DOT VAR(<id>)` **внутри парных
     `{{ … }}`**, вне `STRING`-токенов.
  3. Для каждого: `targetFormOf(literal)` → если не `null`, создать
     `SmartAppFieldReference` с диапазоном = `VAR(<id>)`. Если
     `targetFormOf == null` — **ссылку не создавать** (нет ложного WARNING).
  4. Если ни одной ссылки — вернуть `EMPTY` (поведение как сейчас).
  - `isJinja` остаётся для аннотатора/общего пропуска не-Jinja позиций.

## 5. Диапазоны по raw-тексту literal — `reference/SmartAppJinjaOffsets.kt`

**Контракт.** `JsonStringLiteral.value` — **декодированный** текст (раскрытые
escape), тогда как `TextRange`/`HighlightInfo` оперируют **исходным JSON-текстом**
(с кавычками и escape). Поэтому:
- Лексер работает по **raw-тексту** = `literal.text` минус крайние кавычки
  (`literal.text` уже содержит кавычки; raw-смещение = позиция внутри `literal.text`
  после открывающей кавычки).
- **Безопасное отображение decoded→source НЕ реализуется** (дорого и хрупко);
  вместо этого лексер оперирует исходными символами JSON-строки как есть (включая
  `\n`, `\"`).
- Все `TextRange` из лексера транслируются в absolute-диапазоны через
  `literal.textOffset + 1 + rawOffset` (+1 за открывающую кавычку). Фиксируется в
  общем хелпере и используется contributor'ом (ссылки) и аннотатором (подсветка).
- Escape-последовательности внутри `STRING`-токена подсвечиваются как часть
  `STRING` (корректно).

## 6. Completion — ранняя Jinja-ветка — правка `completion/SmartAppCompletionContributor.kt`

В `addVariants` **первой** проверкой: позиция каретки в конкретном
`{{ main_form.<caret> }}`.
- Вычислить raw-смещение каретки = `parameters.offset - literal.textOffset - 1`.
- Если raw-текст literal содержит парное `{{ … }}`, охватывающее каретку, и
  непосредственно перед кареткой стоит `main_form.` (по raw-лексеме) — определить
  форму через `targetFormOf(literal)`; если форма есть, отдать
  `SmartAppFormFieldNameIndex.allNames(project, form,
  SmartAppScopes.forFile(parameters.originalFile))`. Приоритет 30.0,
  `typeText = "field"`. **Dumb-guard** на чтение индекса.
- **После Jinja-ветки — `return`** (не падать в существующую ссылочную ветку, иначе
  формы предложатся внутри Jinja).
- Позиция определяется по **raw literal** (п.5), не по декодированному `value`.
- Scope берётся от `parameters.originalFile` (как уже сделано для обычной completion).

## 7. Annotator + новые TextAttributes — правка `annotator/SmartAppAnnotator.kt` + `highlight/SmartAppTextAttributes.kt` + `SmartAppColorSettingsPage.kt`

**Новые атрибуты** (5) в `highlight/SmartAppTextAttributes.kt`:
`SMARTAPP_JINJA_DELIM`, `SMARTAPP_JINJA_VAR`, `SMARTAPP_JINJA_OP` (`.`),
`SMARTAPP_JINJA_FILTER` (`|` + имя), `SMARTAPP_JINJA_STRING`. Цвета по умолчанию
наследуются от подходящих `DefaultLanguageHighlighterColors`. Зарегистрировать в
`SmartAppColorSettingsPage` (дескрипторы + demo-теги `<delim>`/`<var>`/…).

**В аннотаторе:** для Jinja-literal — прогнать `SmartAppJinjaLexer`, для каждого
токена положить silent-аннотацию `INFORMATION` с **raw-translated** `TextRange`
(п.5) + соответствующим атрибутом.
- Распознанный `main_form.<id>`: если резолв через индекс успешен — `VAR` без
  WARNING; если **`targetFormOf != null`** и поле не найдено — `WARNING` «Не удаётся
  разрешить поле '<field>' формы '<form>'» (под `DumbService.isDumb` guard, как
  существующий unresolved). Если `targetFormOf == null` — **WARNING не ставится**
  (динамическая форма).
- Нераспознанные идентификаторы (не `main_form.*`), фильтры, `{% %}` — **только
  цвет**, без WARNING.

## 8. Find Usages полей — правка `findusages/SmartAppFindUsagesProvider.kt` + `findusages/SmartAppElementDescriptionProvider.kt`

Использовать общий предикат `isFieldDefinition` (п.1) в **обоих** провайдерах:
- **`SmartAppFindUsagesProvider`:** `canFindUsagesFor` → `true` и для top-level
  определения, и для поля формы. `getType` → `"field"` для поля (отдельная ветка).
- **`SmartAppElementDescriptionProvider.getElementDescription`** для поля:
  - `UsageViewTypeLocation` → `"field"`;
  - **`UsageViewLongNameLocation` → `"<form>.<field>"`** (например
    `"hello_form.name"` — явный формат зафиксирован; правка обязательна, сейчас
    провайдер возвращает `null` для поля, т.к. `kindOf` опознаёт только top-level).
- Штатный `ReferencesSearch` по word-индексу найдёт использования имени поля в
  Jinja-строках, т.к. на них навешаны `SmartAppFieldReference` с диапазоном на
  имени поля.

## Проверка — новые тесты в `SmartAppDslTest.kt` (или новый `SmartAppFormFieldTest.kt`)

**Обязательные кейсы:**
- **Резолв:** `{{ main_form.name }}` в сценарии с `"form": "hello_form"` →
  `multiResolve` находит `JsonProperty` поля `name`.
- **Jinja во вложенном объекте сценария:** `{{ main_form.name }}` внутри
  action/field/question-объекта сценария → резолвится через подъём к top-level
  `form`.
- **Динамический form:** сценарий с `"form": "{{ main_form.name }}"` → **нет
  ссылки и нет WARNING** на внутренний `main_form.name` (`targetFormOf == null`).
- **Диапазон только на имя поля:** `reference.rangeInElement`/`textRange` покрывает
  `name`, не `main_form.name`.
- **Дубли полей:** `multiResolve().size == 2`.
- **Completion:** каретка после `{{ main_form.` → варианты имён полей формы.
- **Negative:** отсутствие field-completion и WARNING у `{{ x.y }}` (не `main_form`),
  строкового `"main_form.name"` внутри `{{ }}`, `{% if x %}`.
- **Подсветка:** `doHighlighting` проверяет presence всех 5 новых `TextAttributesKey`
  на демо-строке; `DELIM`/`VAR`/`OP`/`FILTER`/`STRING` на корректных raw-диапазонах.
- **Dumb mode:** резолв полей тихий (0); structural/keyword подсветка работает.
- **Scope-изоляция:** поля формы из чужого `static/references` не подтягиваются.
- **Find Usages поля:** `myFixture.findUsages(fieldProp)` находит usage в Jinja-строке
  сценария; в т.ч. ассерт
  `getElementDescription(fieldProp, UsageViewLongNameLocation.INSTANCE) == "hello_form.name"`.
- **Битый FORM-файл:** не индексирует собственные поля (по аналогии с J-тестами
  Плана 1); валидная форма рядом работает.
- **Regression:** существующие `testJinjaValueIsNotAReference` и
  `testMixedJinjaValueIsNotAReference` остаются зелёными (в т.ч. `a_{{ x }}_b` — нет
  `main_form.` → нет ссылки).

**Сборка:** `./gradlew buildPlugin` + `verifyPlugin` зелёные; ручная проверка в
sandbox-IDE (`./gradlew runIde`) на тестовых данных.

## Замечания

- **Зависимость от Плана 1:** общий хелпер `JsonPsi.hasError` должен быть уже в коде.
  Поэтому реализация Плана 2 начинается **после** завершения Плана 1.
- **Версионирование индексов** (политика из `AGENTS.md`): `SmartAppFormFieldIndex`
  наследует версию от externalizer'а; `SmartAppFormFieldNameIndex` имеет свою версию
  `1`. При смене формата сериализации соответствующая версия поднимается.

## Риски (явно зафиксировать)

1. **Нет боевых образцов Jinja** — реальный синтаксис (фильтры, `variables.*`,
   подобъекты) неизвестен. План сознательно ограничивается `main_form.<field>`; при
   получении реальных файлов — отдельная итерация расширения лексера.
2. **Целевая форма неоднозначна** — `targetFormOf` возвращает `null` для
   динамической/отсутствующей `form`; ссылка/WARNING не ставятся.
3. **Ложные unresolved** — WARNING только для распознанного `main_form.<id>` с
   известной формой, не найденного в индексе; гарантированно невозможно.
4. **Диапазоны/escape** — работа строго по raw-тексту literal (п.5); escape внутри
   `STRING`-токена подсвечиваются как `STRING`.
5. **Dumb mode** — все чтения нового индекса под `DumbService.isDumb` guard.
6. **Дубли имён полей** — список offset; `multiResolve().size == 2`.
7. **Перекрытие встроенного JSON-хайлайтера** — новая подсветка применяется только
   к фрагментам внутри строковых literal через `silentAnnotation` с локальным
   raw-range; PSI-структура JSON не затрагивается.

## Порядок коммитов (один логический шаг — один коммит)

1. `feat: добавить модель полей форм (FormFieldRef, targetFormOf, isFieldDefinition)`
2. `feat: добавить детерминированный лексер Jinja и raw-смещения`
3. `feat: добавить индексы полей форм (SmartAppFormFieldIndex, SmartAppFormFieldNameIndex)`
4. `feat: резолвить поля форм в Jinja (SmartAppFieldReference, правки contributor)`
5. `feat: автодополнять имена полей внутри Jinja`
6. `feat: подсвечивать Jinja-разметку (TextAttributes, annotator, color page)`
7. `feat: Find Usages для полей форм (формат подписи <form>.<field>)`
8. `test: тесты полей форм и Jinja`

## Future work (вне этого плана)

- Расширение лексера (`variables.*`, фильтры с аргументами, подобъекты `x.y.z` по
  схеме поля) — после получения боевых образцов.
- Go-to-symbol для полей форм (поиск по имени вне конкретной формы).
- JSON Schema provider для структурной валидации значений полей формы.
