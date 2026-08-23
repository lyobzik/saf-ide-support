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
- навигация, автодополнение и подсветка полей форм в Jinja-выражениях
  `{{ main_form.<field> }}` и `{% … main_form.<field> … %}`; у цепочки
  `main_form.a.b` семантику получает только первый сегмент;
- автодополнение самой переменной `main_form` на месте идентификатора внутри
  выражения Jinja (`{% if mai<caret> %}`), если целевая форма известна; после
  `|` (позиция имени фильтра) не предлагается ни переменная, ни поле;
- переход с самой переменной `main_form` на определение целевой формы;
- ключевые слова, зарегистрированные самим приложением (`RESOURCES` в
  `app_config.py` → подкласс `SmartAppResources` → методы `init_*`): подсветка,
  автодополнение, переход к строке регистрации в `.py` и поиск использований;
- переход к файлу шаблона: `"file"` при `"type": "unified_template"` ведёт в
  `static/references/templates/<значение>`; в той же позиции автодополняются
  имена файлов каталога шаблонов (вложенные пути — целиком).

## Монорепозиторий и два контракта

```text
idea-plugin/        # плагин IntelliJ (Kotlin, Gradle)
vscode-extension/   # расширение VS Code (TypeScript, npm)
shared/             # общие контракты: rules, keywords, fixtures
tools/              # генератор словаря
.gitlab-ci.yml      # полный пайплайн (основной хостинг)
.github/workflows/  # лёгкий пайплайн зеркала
tools/ci/           # общая логика CI: шаги, окружение, публикация
```

Системы сборки не объединяются: Gradle собирает плагин, npm — расширение.
Gate'ов тоже два — GitLab (полный) и GitHub (лёгкий), но проверки у них общие:
они описаны в `tools/ci/` и вызываются обоими конфигами.
Согласованность держат **два** контракта, у каждого своя fail-closed проверка:

| Контракт | Артефакт | Проверка |
|---|---|---|
| Данные: виды, каталоги, сегменты пути, ссылочные правила (включая файловые), ключи контекста `type`, структурные ключи, `form`/`fields`, `main_form`, реестры фреймворка (`keywordRegistries`), правила сканирования ресурсов (`resourceScan`), словарь | `shared/rules/rules.json`, `shared/keywords/keywords.json` (+ схемы) | `:idea-plugin:exportRules --check`, `generate_keywords.py --check`, `npm run verify:contract` |
| Поведение: резолв, диагностика, completion, Jinja, изоляция наборов, строгость индексации | `shared/fixtures/**` | `SmartAppConformanceTest` (Kotlin) и `test/conformance` (TS) на одном корпусе |

Правила работы с контрактом:

- **`rules.json` не редактируется руками** — это снимок Kotlin-таблиц
  (`contract/SmartAppContract.kt`), генерируемый таском `exportRules`.
  Меняется семантика → правятся таблицы → перегенерируется снимок.
- `contractVersion` живёт в `SmartAppContract.VERSION` и увеличивается при любом
  **несовместимом** изменении структуры данных; синхронно правятся `const` в
  `shared/rules/rules.schema.json` и `EXPECTED_CONTRACT_VERSION` в
  `vscode-extension/src/core/contract.ts`. Четвёртого места нет:
  `scripts/verify-contract.mjs` читает версию из `contract.ts`, а не хранит
  собственную копию — раньше хранил, и синхронный бамп трёх мест всё равно
  ронял сборку.
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
| `resources/colorSchemes/SmartApp{Default,Darcula}.xml` | Цвета по умолчанию (`additionalTextAttributes`): без них подсветка Jinja не видна |
| `highlight/SmartAppColorSettingsPage` | Страница «SmartApp DSL» в Settings → Color Scheme |
| `annotator/SmartAppAnnotator` | `DumbAware`-аннотатор: подсветка по PSI (включая токены Jinja) + WARNING на битых ссылках и неразрешённых полях форм (ветки с индексом под dumb-guard) |
| `SmartAppTypeContext` | Категория ключевых слов `type` по PSI-контексту (общая для completion и подсветки); `fields`→`field_description`, action-контейнеры→`action`, `requirement` **самого поля** внутри `fields`→`field_requirement`, но требование действия внутри поля (`fields.<f>.on_filled_actions[].requirement`) — обычный `requirement` |
| `SmartAppScopes` | Область поиска для резолва/completion — каталог `references` исходного файла (изоляция наборов `static/references`) |
| `index/SmartAppDefinitionIndex` | `FileBasedIndexExtension` с составным ключом `"<KIND>:<name>"`, value = список offset'ов (сохраняет дубликаты ключей через PSI `getPropertyList()`) |
| `index/SmartAppNameIndex` | `FileBasedIndexExtension` с ключом = вид сущности, value = имена определений файла; для автодополнения имён без `getAllKeys`-скана |
| `index/SmartAppFormFieldIndex` | `FileBasedIndexExtension<FormFieldRef, …Value>` — поля форм `forms.<form>.fields.<field>` (дубли полей сохраняются) |
| `index/SmartAppFormFieldNameIndex` | Ключ = имя формы, value = имена её полей; для completion полей в Jinja |
| `index/SmartAppDefinitionValue` + `…Externalizer` | Значение индекса определений и его var-int сериализация (версия в `getVersion()`) |
| `reference/SmartAppRefRules` | Замороженная таблица правил «условие на узел → target kind(s)» |
| `reference/SmartAppFileRefRules` + `SmartAppFileReference` | Файловые ссылки (`"file"` при `unified_template`): резолв по пути в каталоге `templates`, без индекса; `findByPath` сверяет регистр каждого сегмента, `isOfferablePath` — предикат JSON-safe имён для completion |
| `reference/SmartAppTemplateFiles` | Пути файлов каталогов правила для completion: обход VFS с отменой и обрывом циклов по каноническому пути (индекса для этих файлов нет) |
| `reference/SmartAppReference` | `PsiPolyVariantReferenceBase`, резолв через индекс (под dumb-guard) |
| `reference/SmartAppReferenceContributor` | Навешивает ссылки на `JsonStringLiteral` (только значения JSON, не ключи): кросс-ссылки, ссылки на поля форм и на саму переменную `main_form` в выражениях Jinja |
| `reference/SmartAppJinjaLexer` | Детерминированный лексер Jinja-фрагментов внутри строкового литерала (по decoded-тексту); `fieldCandidates`/`formVariableRanges` работают и в `{{ }}`, и в `{% %}`, у цепочки берётся первый сегмент |
| `reference/JsonStringLiteralDecoder` | decoded-текст литерала (раскрытые JSON escape) + карта `decoded→raw` смещений для трансляции диапазонов |
| `reference/SmartAppFieldRef` + `FormFieldRefDescriptor` | `FormFieldRef(form, field)` — типизированный ключ поля (FIELD не расширяет `SmartAppRefKind`); `targetFormOf` / `isFieldDefinition` |
| `reference/SmartAppFieldReference` | Поли-вариантная ссылка на поле формы; диапазон только на имя поля |
| `reference/SmartAppFormVariableReference` | Ссылка с переменной `main_form` на определение целевой формы |
| `reference/SmartAppCustomKeywordReference` | Мягкая поли-вариантная ссылка со значения `type` на регистрацию в Python-коде приложения; индекс не читает, поэтому dumb-guard не нужен |
| `resources/SmartAppResourceScanner` | Детерминированный разбор Python: маскирование строк и комментариев, логические строки, стек блоков, формы регистрации (порт `core/pythonScan.ts`) |
| `resources/SmartAppResourceResolver` | Цепочка от `RESOURCES` и свёртка регистраций по правилам Python (`super()`, переопределение); fail-closed на цикле, глубине и множественной базе (порт `core/resourceKeywords.ts`) |
| `resources/SmartAppCustomKeywords` | Словарь приложения по VFS с кэшем `CachedValuesManager` (без `FileBasedIndex`, поэтому работает и в dumb mode); правила владения и исключённых каталогов |
| `resources/SmartAppRegistrationElement` | `FakePsiElement` строки регистрации: файл, сырой диапазон имени, навигация; `setName` бросает `IncorrectOperationException` |
| `completion/SmartAppCompletionContributor` | `DumbAware`-автодополнение ключевых слов, имён сущностей, полей формы после `main_form.<caret>` и самой переменной `main_form` на месте идентификатора — в любом выражении Jinja (`{{ }}` и `{% %}`) — и имён файлов шаблонов в позиции `"file"` |
| `findusages/SmartAppCustomKeywordUsages` + `SmartAppFindUsagesHandlerFactory` | Цель поиска по элементу под кареткой и обход JSON-файлов приложения (без пятого индекса), с уважением к `LocalSearchScope` и `checkCanceled` |
| `findusages/SmartAppFindUsagesProvider` + `…ElementDescriptionProvider` | Find Usages для определений и полей форм (подпись `<form>.<field>`); вхождения псевдонима `main_form` в список не входят — имени формы в тексте нет |
| `rename/SmartAppRenameProcessor` | Ограничивает rename ссылками плагина в своём наборе `references`: иначе платформа переписывает одноимённые ключи чужих наборов |
| `contract/SmartAppContract` + `SmartAppSpecs` | Таблицы данных DSL (виды, ссылочные правила, ключи контекста, структурные ключи, пути, `main_form`, реестры фреймворка и правила сканирования ресурсов) — их использует рантайм и сериализует экспортёр |
| `contract/ExportRules` | Сериализация тех же таблиц в `shared/rules/rules.json`; режим `--check` для CI |

Исходники расширения: `vscode-extension/src/`

| Компонент | Назначение |
|---|---|
| `core/contract.ts` | Единственный потребитель `shared/`: сверяет `contractVersion`, отдаёт типизированные данные |
| `core/files.ts`, `core/refKind.ts` | `kindOf`/`referencesRoot` по URI; каталоги и виды — из контракта |
| `core/ast.ts` | Обёртки над `jsonc-parser` вместо PSI-навигации |
| `core/jsonDecode.ts`, `core/jinjaLexer.ts` | Порты `JsonStringLiteralDecoder` и `SmartAppJinjaLexer` (1:1) |
| `core/refRules.ts`, `core/fileRefRules.ts`, `core/typeContext.ts`, `core/fieldRef.ts` | Порты одноимённых Kotlin-объектов |
| `core/indexGate.ts` | Строгость индексации — эквивалент `JsonPsi.hasError` |
| `core/pythonScan.ts`, `core/resourceKeywords.ts` | Порты сканера Python и резолвера ресурсов приложения (1:1 с `resources/**` плагина) |
| `core/index.ts` | Воркспейс-индекс вместо четырёх `FileBasedIndex`, обратный индекс использований и реестр не-DSL файлов набора (для файловых ссылок) |
| `core/semantics.ts`, `core/completion.ts`, `core/semanticTokens.ts`, `core/rename.ts` | Резолв, диагностика, автодополнение, подсветка, переименование — всё в смещениях |
| `vscode/workspace.ts` | `findFiles`, watcher (JSON и `**/*.py`), дебаунс, хранилище текстов; кормит ядро через `upsert`/`remove`, сканирования Python выполняются строго по одному |
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
| Регенерация словаря | `python tools/generate_keywords.py [path-to-resources__init__.py]` (проверка: `--check`) — **только после** `exportRules`: таблицу реестров генератор читает из `rules.json` |
| Тесты расширения | `npm --prefix vscode-extension test` |
| Интеграционные тесты VS Code | `npm --prefix vscode-extension run test:integration` |
| Бандл расширения | `npm --prefix vscode-extension run compile` |
| Упаковка расширения (vsix) | `npm --prefix vscode-extension run package` → `dist/smartapp-dsl-<version>.vsix` |
| Упаковка плагина в `dist/` | `./gradlew :idea-plugin:packagePlugin` → `dist/smartapp-dsl-<version>.zip` |
| Упаковка обеих реализаций | `tools/package.sh` |
| Любой шаг CI локально | `tools/ci/run.sh <шаг>…` (список шагов — в самом файле) |
| Сверка версий манифестов | `sh tools/check-version.sh` |

Порядок при изменении таблицы реестров (`keywordRegistries`) жёсткий — сначала
Kotlin-таблицы, потом словарь, иначе генератор возьмёт прежний снимок:

```bash
./gradlew :idea-plugin:exportRules        # 1. Kotlin-таблицы -> shared/rules/rules.json
python tools/generate_keywords.py         # 2. читает таблицу оттуда -> keywords.json
./gradlew :idea-plugin:exportRules --check && python tools/generate_keywords.py --check
npm --prefix vscode-extension run verify:contract
```

В CI джобы независимы — они проверяют уже закоммиченные снимки; порядок важен
локально.

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
.gitlab-ci.yml                                     # полный пайплайн: контракты, тесты, упаковка, релиз
.github/workflows/ci.yml                           # лёгкий пайплайн зеркала
tools/ci/run.sh, deps-debian.sh, publish_gitlab.py # общая логика CI и публикация
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
  создаются (динамические шаблоны — не «битая ссылка»), но внутри выражений
  Jinja работает семантика полей форм: `main_form.<field>` резолвится,
  дополняется и подсвечивается (WARNING при известной форме) и в интерполяциях
  `{{ … }}`, и в statement-тегах `{% … %}` — в бою обращение к полю одинаково
  часто стоит в `{% if … %}`. Границы: у цепочки `main_form.a.b` семантику
  получает только первый сегмент (`a`), хвост — нет, потому что схемы значений
  полей не существует; `main_form` в позиции чужого поля
  (`variables.main_form.x`) семантики не получает вовсе.
- **Вариант Jinja заменяет слово целиком.** Каретка посреди слова — обычное дело
  (`main_|form`), и вставка «поверх префикса» дала бы `main_formform`. В
  расширении это диапазон замены варианта, в плагине —
  `REPLACE_IDENTIFIER_TAIL`: платформенный insert handler по умолчанию хвост не
  трогает, что проверено мутацией.
- **Целевая форма зависит от вида файла:** в сценарии её называет top-level
  свойство `form`, в файле формы `main_form` — это само top-level определение,
  внутри которого стоит литерал.
- **`main_form` — псевдоним, а не имя.** С переменной работает переход на
  определение формы, но её имени в тексте нет: переименование формы эту строку
  не переписывает, в Find Usages формы она не попадает (платформа ищет по
  слову-имени, которого здесь нет), и неразрешённая форма здесь не
  подсвечивается — об этом уже сообщает диагностика на самом значении `form`.
- **Словарь ключевых слов — это пол, а не потолок.** Приложение добавляет свои
  слова через `RESOURCES` в `app_config.py`, и они работают наравне с
  фреймворковыми. Правила разбора цепочки (порт 1:1 с обеих сторон): базовые
  регистрации учитываются **только** при текстовом `super()`, при совпадении
  пары «категория + имя» побеждает производный класс, **любая** множественная
  база отключает разбор целиком (библиотечный миксин невидим сканеру, но
  участвует в MRO). Слова фреймворка при этом не вычитаются никогда: снимок
  `keywords.json` — единственный их источник, гасить категорию из-за текстовой
  эвристики про `super()` дороже, чем оставить лишнее слово.
- **Приложение — каталог, содержащий `static/references`.** Отсюда владение:
  вложенный `subapp` — отдельное приложение, его модули в словарь внешнего не
  идут и наоборот. Во вхождениях Find Usages действуют **два разных** фильтра:
  набор внутри соседнего `venv` отсекает владение (у него свой корень
  приложения), а зависимость, вендоренная внутрь самого набора, — только список
  исключённых каталогов из контракта. Оба случая закреплены кейсами корпуса.
- **Ресурсы приложения читаются без `FileBasedIndex`.** Цепочка от `RESOURCES` —
  два-три файла; `SmartAppCustomKeywords` берёт их по VFS и кэширует
  `CachedValuesManager` (провайдер не удерживает PSI — платформа это проверяет).
  Побочный выигрыш: dumb-guard не нужен, кастомные слова доступны и во время
  индексации. Единица пересборки — приложение целиком: правка базового класса
  меняет словарь производного.
- **Имя регистрации декодируется, диапазон остаётся сырым.** Имя сравнивается со
  значением JSON, которое мы тоже декодируем (`"custom\u0041"` → `customA`), а
  диапазон служит целью перехода и вхождением — поэтому `getText()`
  fake-элемента читается из файла, а не из имени.
- **Направление «каретка в Python» — платформенное, не наше.** Без плагина
  Python `.py` — plain text, элемента нужного вида не существует, и
  автоматический гейт его не покрывает: в тестовой платформе `python-ce` нет.
  Проверяется вручную при приёмке и описано в README.
- **Ссылка на файл — отдельный вид правила.** `SmartAppSpecs.fileRefRules`
  (`"file"` при `"type": "unified_template"`) резолвится по пути внутри набора
  `references`, а не через индекс определений, поэтому dumb-guard ей не нужен.
  Значение содержит расширение, вложенные пути допускаются, а выход за пределы
  каталога (`..`, ведущий `/`) цели не даёт и **ошибкой не считается**.
  Происхождение правила: ключа `file` нет в публичных исходниках фреймворка —
  правило выведено из раскладки эталонного приложения (см. комментарий в
  `SmartAppContract.kt`).
- **Путь шаблона регистрозависим во всех сегментах.** `findFileByRelativePath`
  полагается на ФС, и на macOS `Items.JINJA2` нашёл бы `items.jinja2` — тогда
  диагностика зависела бы от машины разработчика, а не от проекта.
  `SmartAppFileRefRules.findByPath` сверяет фактическое имя каждого сегмента;
  расширение сравнивает пути точно и так. Фикстуры `myFixture` живут на
  регистрозависимой in-memory ФС, поэтому регрессию ловит отдельный тест на
  настоящей ФС (`SmartAppFileRefRulesTest`), а корпус фиксирует контракт.
- **Автодополнение файлов шаблонов — без гарантий по размеру каталога.** Лимит
  не вводится сознательно: платформы фильтруют по префиксу уже собранный набор,
  поэтому выброшенный файл не появился бы и после того, как пользователь набрал
  его имя целиком. Вместо лимита — отменяемый обход и обрыв циклов. Предлагаются
  только имена, которые пишутся в JSON без экранирования (нет `"`, `\` и
  символов с кодом < U+0020): для них decoded-текст совпадает с сырым. Предикат
  обязан посимвольно совпадать в обеих реализациях (`isOfferablePath`), это
  закреплено одной таблицей входов в тестах с обеих сторон.
- **Символические ссылки следуются, паритет — только при настройках по
  умолчанию.** Внутри каждой реализации политика одна для резолва и completion:
  ссылка-файл резолвится и предлагается, каталог-ссылка обходится, цикл
  обрывается по каноническому пути. Но состав реестра в VS Code определяет
  `workspace.findFiles`, а он подчиняется настройке редактора
  `search.followSymlinks` (по умолчанию включена); плагин IDEA следует ссылкам
  всегда. Это осознанное исключение, а не единая политика.
- **Подсветка обязана быть видимой.** Наследоваться от `BRACES`/`DOT`/
  `OPERATION_SIGN` нельзя: в схемах платформы у них нет своего цвета, а
  silent-аннотация при этом перекрывает цвет строки JSON — фрагмент Jinja
  становится неотличим от обычного текста. Цвета по умолчанию везёт плагин
  (`resources/colorSchemes/`), в VS Code роль дефолта играет список
  fallback-scope'ов. И то и другое проверяется тестами
  (`SmartAppColorSchemesTest`, тест манифеста).
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
- **Переопределение «внутри `fields`» действует только на само поле.** У
  фреймворка два разных реестра: `field_requirements` наполняет требования поля
  (`fields.<f>.requirement`), а `requirements` — требования действий, в том
  числе действий внутри описания поля
  (`fields.<f>.on_filled_actions[].requirement`). Различает их action-контейнер
  **между** ключом и `fields`: есть — переопределение не применяется. Без этого
  `"type": "template"` в форме считался бы неизвестным словом, хотя эталонное
  приложение пишет его именно там.
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
варианты автодополнения, токены) и **вид** варианта (`kind`) — да; **порядок**
имён в индексе и вариантов автодополнения — нет, его задают сортировщики платформ.
Вид проверяют оба раннера: в расширении он приходит полем результата, в плагине —
пометкой `SmartAppCompletionKind` на lookup-элементе (авто-вставку единственного
варианта раннер отключает, иначе проверять было бы нечего). Каретку в completion-кейсах
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
- Фикстура корпуса может содержать `.py` приложения (`app_config.py` и модули
  ресурсов) — их читают обе стороны. Каретку в `.py` ставить нельзя: в тестовой
  платформе это plain text. Словарь приложения проверяет секция
  `customKeywords` (состав + место действующей регистрации), формат — в
  `shared/fixtures/README.md`.

### CI/CD

Проверки живут в `tools/ci/`, а не в YAML, и потому одинаковы на обоих
хостингах и на машине разработчика. Роли строго разделены:

| Файл | Что в нём | Критерий |
|---|---|---|
| `tools/ci/run.sh` | шаги сборки и проверок | воспроизводится локально |
| `tools/ci/deps-debian.sh` | `apt-get` для интеграционного теста | зависит от образа |
| `tools/ci/publish_gitlab.py` | реестр пакетов и Release | зависит от API хостинга |

Правила, которые легко нарушить незаметно:

- **в YAML не пишутся команды** — только вызовы этих скриптов. Иначе два
  конфига начинают расходиться, и заметить это можно лишь по разному цвету
  пайплайнов;
- **в `run.sh` нет переменных хостинга**: тег передаётся аргументом
  (`grep -c 'CI_[A-Z]\|GITHUB_' tools/ci/run.sh` обязан давать 0);
- **сторона IDEA — только в GitLab.** Снимок `rules.json` проверяется первой
  задачей внутри `test:idea`, а не на стадии `contract`: `exportRules` зависит
  от `sourceSets.main.output`, то есть компилирует плагин и тянет платформу.
  Вернуть проверку в дешёвую стадию можно, выделив модуль `:contract`;
- **список IDE для `verifyPlugin` задан явно** (`pluginVerification.ides` в
  `idea-plugin/build.gradle.kts`). По умолчанию плагин платформы берёт
  `recommended()` — это пять дистрибутивов IDE на каждый прогон, и набор молча
  растёт с каждым релизом JetBrains. Проверяются два края обещанного диапазона:
  `platformVersion` (нижний, он же `sinceBuild`) и `verifierLatestVersion`
  (верхний, потому что `untilBuild` не задан);
- **релиз выпускает только GitLab.** Публикация идемпотентна: состояние реестра
  читается до записи, отказ `PUT` разбирается по состоянию, а не по коду
  ответа, набор ссылок и коммит тега сверяются и у существующего релиза, и у
  только что созданного. Последнее — не перестраховка: атомарной привязки
  релиза к коммиту в API нет, неизменность даёт protected tag `v*`, а скрипт
  обязан немедленно показать её нарушение. Саму настройку он тоже проверяет:
  незащищённый тег — отказ до единой загрузки в реестр; список защищённых тегов
  нечитаем — тоже отказ. Сигнала из окружения, годного в доказательство, не
  существует: переменные вручную запущенного пайплайна перекрывают
  предопределённые, включая `CI_COMMIT_REF_PROTECTED`. Поэтому список читается
  отдельным токеном проекта (`SMARTAPP_API_TOKEN`, область `read_api`, scope
  окружения `release` — со scope `*` его читал бы любой job пайплайна), адрес
  API и путь проекта зафиксированы константами в скрипте (расхождение с
  окружением — отказ; переезд проекта требует правки константы), а
  `CI_COMMIT_SHA` используется лишь как вспомогательная сверка. Алгоритм закреплён тестами на
  фейковом транспорте (`tools/ci/run.sh test:publish`), поведение настоящего
  API — ручным job'ом `release:smoke`.

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
