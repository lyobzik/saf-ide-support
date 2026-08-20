# SmartApp DSL — плагин для IntelliJ IDEA и расширение для VS Code

## Обзор проекта

Плагин для IntelliJ-платформы, добавляющий поддержку DSL-сценариев фреймворка
**Sber SmartApp Framework** (`smart_app_framework`). DSL описывается **JSON-файлами**
в каталоге `static/references/` (подкаталоги `scenarios/`, `forms/`, `actions/`,
`behaviors/`, `field_fillers/`, `classifiers/`). Верхнеуровневые ключи каждого
файла — это именованные определения сущностей; «ключевые слова» — значения поля
`type`, зарегистрированные в исходниках фреймворка.

Поддержка реализована **дважды**: плагин для IntelliJ-платформы (`idea-plugin/`)
и расширение для VS Code (`vscode-extension/`). Ни одна из реализаций не вводит
свой язык/лексер — обе строят **семантический слой поверх встроенного
JSON-парсера редактора**. Возможности (одинаковые с обеих сторон):

- подсветка ключевых слов и структурных ключей;
- переход к определению сущности (Go to Definition / Ctrl+Click);
- поиск использований (Find Usages / Alt+F7);
- автодополнение значений `type` и ссылочных ключей;
- подсветка неразрешённых ссылок (severity **WARNING**);
- навигация, автодополнение и подсветка полей форм в Jinja-интерполяциях
  `{{ main_form.<field> }}` (scope — только плоский доступ к полю).

## Монорепозиторий и два контракта

```text
idea-plugin/        # плагин IntelliJ (Kotlin, Gradle)
vscode-extension/   # расширение VS Code (TypeScript, npm)
shared/             # общие контракты: rules, keywords, fixtures
tools/              # генератор словаря
.gitlab-ci.yml      # единственный общий gate
```

Системы сборки не объединяются: Gradle собирает плагин, npm — расширение.
Согласованность держат **два** контракта, у каждого своя fail-closed проверка:

| Контракт | Артефакт | Проверка |
|---|---|---|
| Данные: виды, каталоги, сегменты пути, ссылочные правила, ключи контекста `type`, структурные ключи, `form`/`fields`, `main_form`, словарь | `shared/rules/rules.json`, `shared/keywords/keywords.json` (+ схемы) | `:idea-plugin:exportRules --check`, `generate_keywords.py --check`, `npm run verify:contract` |
| Поведение: резолв, диагностика, completion, Jinja, изоляция наборов, строгость индексации | `shared/fixtures/**` | `SmartAppConformanceTest` (Kotlin) и `test/conformance` (TS) на одном корпусе |

Правила работы с контрактом:

- **`rules.json` не редактируется руками** — это снимок Kotlin-таблиц
  (`contract/SmartAppContract.kt`), генерируемый таском `exportRules`.
  Меняется семантика → правятся таблицы → перегенерируется снимок.
- `contractVersion` живёт в `SmartAppContract.VERSION` и увеличивается при любом
  **несовместимом** изменении структуры данных; синхронно правятся `const` в
  `shared/rules/rules.schema.json` и `EXPECTED_CONTRACT_VERSION` в
  `vscode-extension/src/core/contract.ts`.
- Новая семантика **без нового кейса в `shared/fixtures` не принимается**: общий
  `rules.json` фиксирует данные, но не алгоритмы.
- В `vscode-extension/src/core/**` не должно быть литералов DSL-имён
  (`"scenarios"`, `"static"`, `"form"`, `"fields"`, `"main_form"`, `".json"`) —
  они приходят из контракта через единственный модуль `contract.ts`; eslint
  проверяет запрет импорта, остальное — на ревью.
- То же правило действует и в Kotlin: имена ключей берутся из
  `FieldAccessSpec`/`TypeContextSpec`, а не пишутся строкой в индексаторах и
  провайдерах. Соответствие «структурный ключ → категория ключевых слов» тоже
  часть контракта (`typeContext.keyCategories`), а не `when`-ветка в двух
  реализациях сразу.
- **Поиск файлов в VS Code идёт по контракту**: glob строится из
  `paths.rootSegments`, а расширение файла в шаблон не входит — контракт
  допускает игнорирование регистра (`.JSON`), а glob в VS Code
  регистрозависим. Отбор делает `isDslFile`.

## Ключевые компоненты

Исходники плагина: `idea-plugin/src/main/kotlin/ru/sber/smartapp/dsl/`

| Компонент | Назначение |
|---|---|
| `SmartAppRefKind` | Перечисление видов сущностей (SCENARIO, FORM, ACTION, BEHAVIOR, FILLER, CLASSIFIER) и их каталогов |
| `SmartAppFiles` | Определяет, является ли JSON-файл DSL-файлом, и его `RefKind` по пути `static/references/<kind>/` |
| `SmartAppKeywords` | Ленивая загрузка `keywords.json`; запросы `isKeyword(category, value)` / `all(category)` |
| `highlight/SmartAppTextAttributes` | Ключи цветовых атрибутов (`SMARTAPP_KEYWORD`, `SMARTAPP_FIELD`, `SMARTAPP_JINJA_*`) |
| `highlight/SmartAppColorSettingsPage` | Страница «SmartApp DSL» в Settings → Color Scheme |
| `annotator/SmartAppAnnotator` | `DumbAware`-аннотатор: подсветка по PSI (включая токены Jinja) + WARNING на битых ссылках и неразрешённых полях форм (ветки с индексом под dumb-guard) |
| `SmartAppTypeContext` | Категория ключевых слов `type` по PSI-контексту (общая для completion и подсветки); `fields`→`field_description`, action-контейнеры→`action`, `requirement` внутри `fields`→`field_requirement` |
| `SmartAppScopes` | Область поиска для резолва/completion — каталог `references` исходного файла (изоляция наборов `static/references`) |
| `index/SmartAppDefinitionIndex` | `FileBasedIndexExtension` с составным ключом `"<KIND>:<name>"`, value = список offset'ов (сохраняет дубликаты ключей через PSI `getPropertyList()`) |
| `index/SmartAppNameIndex` | `FileBasedIndexExtension` с ключом = вид сущности, value = имена определений файла; для автодополнения имён без `getAllKeys`-скана |
| `index/SmartAppFormFieldIndex` | `FileBasedIndexExtension<FormFieldRef, …Value>` — поля форм `forms.<form>.fields.<field>` (дубли полей сохраняются) |
| `index/SmartAppFormFieldNameIndex` | Ключ = имя формы, value = имена её полей; для completion полей в Jinja |
| `index/SmartAppDefinitionValue` + `…Externalizer` | Значение индекса определений и его var-int сериализация (версия в `getVersion()`) |
| `reference/SmartAppRefRules` | Замороженная таблица правил «условие на узел → target kind(s)» |
| `reference/SmartAppReference` | `PsiPolyVariantReferenceBase`, резолв через индекс (под dumb-guard) |
| `reference/SmartAppReferenceContributor` | Навешивает ссылки на `JsonStringLiteral` (только значения JSON, не ключи): кросс-ссылки и ссылки на поля форм в `{{ main_form.<field> }}` |
| `reference/SmartAppJinjaLexer` | Детерминированный лексер Jinja-фрагментов внутри строкового литерала (по decoded-тексту); `fieldCandidates` — только плоский `main_form.<field>` внутри `{{ }}` (цепочки не разбираются) |
| `reference/JsonStringLiteralDecoder` | decoded-текст литерала (раскрытые JSON escape) + карта `decoded→raw` смещений для трансляции диапазонов |
| `reference/SmartAppFieldRef` + `FormFieldRefDescriptor` | `FormFieldRef(form, field)` — типизированный ключ поля (FIELD не расширяет `SmartAppRefKind`); `targetFormOf` / `isFieldDefinition` |
| `reference/SmartAppFieldReference` | Поли-вариантная ссылка на поле формы; диапазон только на имя поля |
| `completion/SmartAppCompletionContributor` | `DumbAware`-автодополнение ключевых слов, имён сущностей и полей формы в `{{ main_form.<caret> }}` (только identifier-имена) |
| `findusages/SmartAppFindUsagesProvider` + `…ElementDescriptionProvider` | Find Usages для определений и полей форм (подпись `<form>.<field>`), человекочитаемые подписи |
| `rename/SmartAppRenameProcessor` | Ограничивает rename ссылками плагина в своём наборе `references`: иначе платформа переписывает одноимённые ключи чужих наборов |
| `contract/SmartAppContract` + `SmartAppSpecs` | Таблицы данных DSL (виды, ссылочные правила, ключи контекста, структурные ключи, пути, `main_form`) — их использует рантайм и сериализует экспортёр |
| `contract/ExportRules` | Сериализация тех же таблиц в `shared/rules/rules.json`; режим `--check` для CI |

Исходники расширения: `vscode-extension/src/`

| Компонент | Назначение |
|---|---|
| `core/contract.ts` | Единственный потребитель `shared/`: сверяет `contractVersion`, отдаёт типизированные данные |
| `core/files.ts`, `core/refKind.ts` | `kindOf`/`referencesRoot` по URI; каталоги и виды — из контракта |
| `core/ast.ts` | Обёртки над `jsonc-parser` вместо PSI-навигации |
| `core/jsonDecode.ts`, `core/jinjaLexer.ts` | Порты `JsonStringLiteralDecoder` и `SmartAppJinjaLexer` (1:1) |
| `core/refRules.ts`, `core/typeContext.ts`, `core/fieldRef.ts` | Порты одноимённых Kotlin-объектов |
| `core/indexGate.ts` | Строгость индексации — эквивалент `JsonPsi.hasError` |
| `core/index.ts` | Воркспейс-индекс вместо четырёх `FileBasedIndex` + обратный индекс использований |
| `core/semantics.ts`, `core/completion.ts`, `core/semanticTokens.ts`, `core/rename.ts` | Резолв, диагностика, автодополнение, подсветка, переименование — всё в смещениях |
| `vscode/workspace.ts` | `findFiles`, watcher, дебаунс, хранилище текстов; кормит ядро через `upsert`/`remove` |
| `vscode/providers.ts`, `vscode/positions.ts` | Провайдеры VS Code и трансляция смещений в `Position` |

Ресурсы: `src/main/resources/META-INF/plugin.xml`,
`src/main/resources/keywords/keywords.json` (генерируется).

Генератор словаря: `tools/generate_keywords.py` (AST-парсинг
`smart_kit/resources/__init__.py`; vendored-копия в `tools/vendor/`).

## Технологический стек

- **Kotlin** 2.0.21 (JVM target 21 — платформа 2025.1 требует Java 21)
- целевая платформа: `sinceBuild=251` (IDEA 2025.1+), `untilBuild` не задан
- **IntelliJ Platform Gradle Plugin** 2.16.0 (требует **Gradle 9.0+**)
- **Gradle** 9.0.0 (через wrapper)
- **JBR 21** из локального GIGA IDE (компиляция/запуск; см. «Окружение»)
- сборка против **локальной** IDE через `intellijPlatform { local(...) }`
- бандл-плагин `com.intellij.modules.json` — JSON PSI
- **gson** 2.11.0 — парсинг `keywords.json` (нет в classpath платформы, поэтому бандлится)
- **JUnit 4** + `BasePlatformTestCase` — тесты
- **Python 3** — генератор словаря ключевых слов

## Команды разработки

> Всем `gradle`-командам **обязательно** предшествует экспорт `JAVA_HOME`
> (в системе нет отдельного JDK):
>
> ```bash
> export JAVA_HOME="/Applications/GIGA IDE CE 2025.1.app/Contents/jbr/Contents/Home"
> ```

| Действие | Команда |
|---|---|
| Компиляция плагина | `./gradlew :idea-plugin:compileKotlin` |
| Тесты плагина | `./gradlew :idea-plugin:test` |
| Сборка плагина (zip) | `./gradlew :idea-plugin:buildPlugin` → `idea-plugin/build/distributions/smartapp-dsl-<version>.zip` |
| Запуск sandbox-IDE | `./gradlew :idea-plugin:runIde` |
| Проверка совместимости | `./gradlew :idea-plugin:verifyPlugin` |
| Экспорт контракта данных | `./gradlew :idea-plugin:exportRules` (проверка: `--check`) |
| Сборка против maven-платформы | `./gradlew :idea-plugin:test -PideSource=maven` |
| Регенерация словаря | `python tools/generate_keywords.py [path-to-resources__init__.py]` (проверка: `--check`) |
| Тесты расширения | `npm --prefix vscode-extension test` |
| Интеграционные тесты VS Code | `npm --prefix vscode-extension run test:integration` |
| Бандл расширения | `npm --prefix vscode-extension run compile` |
| Упаковка расширения (vsix) | `npm --prefix vscode-extension run package` → `dist/smartapp-dsl-<version>.vsix` |
| Упаковка плагина в `dist/` | `./gradlew :idea-plugin:packagePlugin` → `dist/smartapp-dsl-<version>.zip` |
| Упаковка обеих реализаций | `tools/package.sh` |
| Сверка версий манифестов | `sh tools/check-version.sh` |

> Gradle в этом окружении иногда не замечает правки, сделанные извне IDE
> (устаревший кэш file-watching), и берёт классы из прошлой компиляции.
> Если тест ведёт себя так, будто изменения не применились, повторите команду с
> `--no-watch-fs`.

**Деплой / установка.** `tools/package.sh` собирает оба артефакта релиза в
`dist/`: `smartapp-dsl-<version>.zip` (плагин) и `smartapp-dsl-<version>.vsix`
(расширение). Скрипт — тонкая обёртка над Gradle и npm, а не общая система
сборки: каждую сторону по-прежнему можно собрать отдельно.

- IDEA: Settings → Plugins → ⚙ → *Install Plugin from Disk…* и выбрать zip.
- VS Code: `code --install-extension dist/smartapp-dsl-<version>.vsix` либо
  Extensions → … → *Install from VSIX…*.

Публикация в Marketplace/OpenVSX в v1 не настроена. Номер версии живёт в двух
манифестах (`version` в `idea-plugin/build.gradle.kts` и в
`vscode-extension/package.json`) — свести их в один источник нельзя, поэтому
расхождение ловит `tools/check-version.sh` (джоба `contract:version`) до
упаковки, а не пользователь по двум несовместимым артефактам.

**Релизный тег — `v<version>`** (например `v0.1.0`). Тег — третье место, где
записана версия, поэтому та же проверка сверяет его с манифестами:
`CI_COMMIT_TAG` передаётся в `check-version.sh`, и `v0.2.0` на манифестах
`0.1.0` роняет пайплайн до стадии `package`. Тег другого вида (`sandbox`)
релизным не считается: на таком пайплайне джобы упаковки не появляются вовсе
(правило `.release` гасит их через `when: never`), потому что проверить такой
тег нечем — кнопка выдала бы артефакты, о версии которых тег ничего не обещает.
Ручная упаковка остаётся на ветках, где тега нет.

Обе стороны собираются в `dist/.staging/` и попадают в `dist/` переносом:
`rename` в пределах одной ФС атомарен, поэтому прерванная упаковка (остановленный
процесс, ошибка ФС) не оставляет опубликованным обрезанный архив — ни рядом с
рабочим, ни поверх него. Для `.vsix` перенос делается ещё и после проверки
состава пакета.

Предыдущий артефакт убирается только после публикации нового и только свой:
`packagePlugin` удаляет `smartapp-dsl-*.zip`, кроме собранного,
`scripts/package.mjs` — `smartapp-dsl-*.vsix`, кроме нового. `dist/` — каталог
релиза, но не собственность этих двух сборок: посторонний файл там не наш,
чтобы его удалять. Порядок «сначала опубликовать» тоже важен: упавшая сборка не
должна оставить `dist/` вообще без дистрибутива.

Состав `.vsix` проверяется при упаковке: `scripts/package.mjs` требует бандл в
пакете и запрещает исходники, тесты и sourcemap'ы. Опечатка в `.vscodeignore`
сборку не ломает — она молча меняет содержимое пакета, поэтому проверка нужна
здесь, а не на ревью.

## Каталоги для планов и исследований AI-агентов

- `docs/plans/` — планы реализации задач;
- `docs/plans/completed/` — полностью реализованные планы (переносятся сюда из
  `docs/plans/` по завершении);
- `docs/insights/` — инсайты, заметки по итогам исследований;
- `arch/` — архитектурные документы и решения;
- `mds/` — прочие рабочие markdown-документы.

Файлы планов в `docs/plans/` именуются с префиксом текущей даты в формате
`YYYY-MM-DD`, например `2026-05-30-имя-плана.md`. Когда план полностью
реализован, переносите его в `docs/plans/completed/` (имя с датой сохраняется).

Эти материалы **на русском языке** (см. «Стиль кода»). Сохраняйте сюда планы и
исследования, чтобы они переживали сессии.

## Формат Git-коммитов

Формат сообщения: `<тип>: <краткое описание>`, где `<тип>` — один из
`feat`, `fix`, `refactor`, `test`, `docs`, `build`, `chore`.

- описание — в повелительном наклонении, кратко (≤ 70 символов);
- подробности — в теле коммита (зачем, а не что);
- по одному логическому изменению на коммит; новые коммиты вместо `--amend`.

## Структура проекта и игнорируемые каталоги

```
settings.gradle.kts, gradle.properties, gradlew    # сборка плагина (Gradle 9)
.gitlab-ci.yml                                     # CI: контракты + тесты обеих сторон
idea-plugin/build.gradle.kts                       # модуль плагина
idea-plugin/src/main/kotlin/ru/sber/smartapp/dsl/  # исходники плагина
idea-plugin/src/main/resources/META-INF/plugin.xml # дескриптор
idea-plugin/src/test/kotlin/                       # тесты плагина
vscode-extension/src/core/, src/vscode/            # ядро и адаптер расширения
vscode-extension/test/                             # тесты ядра, адаптера, корпуса, интеграции
shared/rules/, shared/keywords/, shared/fixtures/  # общие контракты
tools/generate_keywords.py, tools/vendor/          # генератор словаря
tools/package.sh, tools/check-version.sh           # упаковка обеих реализаций
dist/                                              # артефакты релиза (zip + vsix)
docs/plans/, docs/insights/, arch/, mds/           # материалы для AI-агентов
```

Игнорируется (`.gitignore`): `.gradle/`, `build/`, `dist/`, `.tooling/`, `*.iml`,
`.idea/`, `out/`, `.intellijPlatform/`, `node_modules/`,
`vscode-extension/out/`, `vscode-extension/.vscode-test/`, `*.vsix`.

## Важные паттерны

- **Семантический слой над JSON PSI** (`JsonProperty`, `JsonStringLiteral`,
  `JsonObject`) — не вводить свой язык/лексер.
- **Dumb mode:** любое чтение `FileBasedIndex` оборачивается guard'ом
  `if (DumbService.isDumb(project)) return`. Аннотатор и completion помечены
  `DumbAware`: чисто-PSI логика (подсветка ключевых слов) работает и во время
  индексации, индекс-зависимые ветки молчат до её завершения.
- **Дубликаты top-level ключей:** индексатор обходит `JsonObject.getPropertyList()`
  (сохраняет дубли); `findProperty()` их схлопывает — навигация строится по
  сохранённым offset'ам (`multiResolve` отдаёт все).
- **Jinja-значения:** прямые ссылки на сущности из значений с `{{`/`{%` не
  создаются (динамические шаблоны — не «битая ссылка»), но внутри интерполяций
  `{{ … }}` работает семантика полей форм: `{{ main_form.<field> }}` резолвится,
  дополняется и подсвечивается (WARNING при известной форме). Scope — только
  плоский `main_form.<field>`: цепочки (`variables.main_form.x`, `main_form.x.y`)
  и statement-теги `{% … %}` семантики не получают.
- **Jinja-лексер по decoded-тексту** (`JsonStringLiteralDecoder`): JSON escape
  раскрываются до лексинга, диапазоны токенов транслируются в документ картой
  `decoded→raw`. Completion определяет контекст каретки тем же лексером и
  предлагает только identifier-имена полей.
- **FIELD — не `SmartAppRefKind`:** поля форм живут внутри top-level определения
  формы (`forms.<form>.fields.<field>`) и моделируются `FormFieldRef`;
  контракты видов (`kindOf`, `isTopLevelDefinition`) к ним не применимы.
- **Frozen rule table** (`SmartAppRefRules`) — единый источник правды для ссылок,
  unresolved-аннотаций и автодополнения имён.
- **Контекст категории `type`** (`SmartAppTypeContext`) — единый источник правды
  для подсветки и автодополнения значений `type`: категория определяется
  подъёмом по PSI, action-контейнеры (`actions`, `on_filled_actions`,
  `success_action`…) дают `action` раньше, чем `fields`.
- **Изоляция наборов** (`SmartAppScopes`) — резолв/completion ограничены
  каталогом `references` исходного файла, чтобы в монорепо ссылки не утекали в
  чужой `static/references`.
- **Rename изолирован по набору `references`.** JSON-плагин платформы связывает
  одноимённые ключи разных файлов, поэтому штатный рефакторинг переписывал бы
  одноимённые определения в чужих `static/references`. Область переименования
  сужает `SmartAppRenameProcessor` — та же изоляция, что у резолва.
- **Неоднозначные ссылки не переименовываются.** Правило `action` в
  external-обёртке допускает и `ACTION`, и `BEHAVIOR`. Вид определяется по
  фактически найденным определениям: один вид — переименовываем, несколько —
  отказываем с объяснением (`renameLookupAt` возвращает `reason`). Молча выбрать
  первый вид значило бы переименовать не ту сущность.
- **Позиции в корпусе однозначны.** Ожидание описывается строкой и текстом; если
  такой текст встречается в строке дважды, обязательна колонка — иначе кейс не
  различает два одинаковых имени и оба раннера падают.
- **Снимки файлов версионируются.** Чтения асинхронны и завершаются не в том
  порядке, в каком начались, поэтому у каждого URI есть номер поколения:
  «догнавшее» старое чтение не затирает свежее содержимое.
- **Диапазоны: ссылка и диагностика — разные вещи.** Использование покрывает имя
  без кавычек (`rangeInElement` в IDEA), предупреждение — литерал целиком.
- **Строгость JSON применяется только к вкладу файла в индекс.** `JsonPsi.hasError`
  вызывается лишь в индексаторах: подсветка, ссылки, диагностика и completion
  работают и на битом файле (в IDEA — по частичному PSI, в расширении — по
  частичному дереву `jsonc-parser`). Гасить их на каждой незакрытой скобке во
  время набора — не то поведение, что есть у пользователей сегодня.
- **Комментарии и висячие запятые ошибкой не считаются** — так ведёт себя JSON PSI
  IntelliJ. Это измеренное поведение платформы, а не решение: оно зафиксировано
  характеризационными тестами (`SmartAppStrictJsonContractTest`) и определяет
  настройки `jsonc-parser` в расширении. Меняется платформа — сначала правятся
  эти тесты, потом обе реализации.
- **Версионирование индексов:** при изменении формата сериализации увеличивать
  `getVersion()` соответствующего индекса —
  `SmartAppDefinitionExternalizer.SERIALIZATION_VERSION` для индексов определений
  и полей (`SmartAppDefinitionIndex`, `SmartAppFormFieldIndex`) и своя
  `CONTENT_VERSION` у индексов имён (`SmartAppNameIndex`,
  `SmartAppFormFieldNameIndex`) — у каждого индекса свой externalizer и версия.

### Использование SDK / платформы

- Зависимость от JSON-плагина объявляется с **двух сторон**: `plugin.xml`
  (`<depends>com.intellij.modules.json</depends>`) и `build.gradle.kts`
  (`bundledPlugin("com.intellij.modules.json")`).
- `instrumentCode = false` — инструментирование байткода не используется.
- Внешние библиотеки (gson) подключаются как `implementation` и бандлятся в
  `lib/` плагина; на core-classpath платформы JSON-библиотек нет.

### Тестирование

Тестов три уровня, и все три обязательны:

1. **Плагин IDEA** — `BasePlatformTestCase`, включая прогон общего корпуса
   (`SmartAppConformanceTest`).
2. **Ядро расширения** — vitest поверх `src/core`, включая тот же корпус
   (`test/conformance`). Работает без редактора.
3. **Адаптер расширения** — vitest поверх `src/vscode` с фейковым модулем
   `vscode` (`test/mocks/vscode.ts`) плюс smoke в настоящем VS Code
   (`test/integration`). Ядро оперирует смещениями, а пользователь видит
   результат адаптера: ошибки offset→`Position`, `Uri`, диапазона
   `CompletionItem` и содержимого `WorkspaceEdit` видны только здесь.

Что фиксирует корпус, а что нет: **состав** результатов (определения, диагностики,
варианты автодополнения, токены) — да; **порядок** имён в индексе и вариантов
автодополнения — нет, его задают сортировщики платформ. Каретку в completion-кейсах
ставьте в позицию с пустым префиксом, иначе IDEA отфильтрует список своим prefix
matcher'ом, а ядро расширения — нет.

- Интеграционные тесты — `BasePlatformTestCase`; фикстуры добавляются через
  `myFixture.addFileToProject("static/references/<kind>/…", …)`, чтобы сработали
  определение `RefKind` и индексация.
- Resolve проверяется без редактора через `literal.references` →
  `SmartAppReference.multiResolve`.
- В автодополнении использовать `parameters.originalFile` для определения
  `RefKind` (completion работает на in-memory копии, теряющей реальный путь).
- Обязательные негативные/IDE-кейсы: dumb mode, невалидный JSON, дубликаты
  определений (`multiResolve().size == 2`), Jinja, файлы вне `static/references/`.

### Окружение

- Нет системных JDK / Gradle / `gh`. JDK — JBR внутри GIGA IDE
  (`…/Contents/jbr/Contents/Home`), Gradle — через wrapper, GitHub — через
  `curl` к API. Node и npm — есть (нужны расширению).
- `ideSource=local` (по умолчанию) берёт платформу из локальной IDE по
  `localIdePath`; `ideSource=maven` — из maven-репозиториев по `platformVersion`.
  CI работает только со вторым вариантом.

## Стиль кода

- **Docstrings и комментарии — на русском языке** (в обеих реализациях: Kotlin и
  TypeScript).
- **Планы, инсайты и документы — на русском** (`docs/plans/`, `docs/insights/`,
  `arch/`, `mds/`).
- **Сообщения в логах — на английском.**
- Комментарии пишутся только когда неочевидно «почему»; не дублировать «что»
  делает код.
- Kotlin: следовать официальным конвенциям; явные модификаторы видимости там,
  где это улучшает читаемость API.
