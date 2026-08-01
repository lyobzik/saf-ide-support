# SmartApp DSL — плагин для IntelliJ IDEA

## Обзор проекта

Плагин для IntelliJ-платформы, добавляющий поддержку DSL-сценариев фреймворка
**Sber SmartApp Framework** (`smart_app_framework`). DSL описывается **JSON-файлами**
в каталоге `static/references/` (подкаталоги `scenarios/`, `forms/`, `actions/`,
`behaviors/`, `field_fillers/`, `classifiers/`). Верхнеуровневые ключи каждого
файла — это именованные определения сущностей; «ключевые слова» — значения поля
`type`, зарегистрированные в исходниках фреймворка.

Плагин не вводит свой язык/лексер, а строит **семантический слой поверх
встроенного JSON-парсера IDEA**. Возможности:

- подсветка ключевых слов и структурных ключей;
- переход к определению сущности (Go to Definition / Ctrl+Click);
- поиск использований (Find Usages / Alt+F7);
- автодополнение значений `type` и ссылочных ключей;
- подсветка неразрешённых ссылок (severity **WARNING**);
- навигация, автодополнение и подсветка полей форм в Jinja-интерполяциях
  `{{ main_form.<field> }}` (scope — только плоский доступ к полю).

## Ключевые компоненты

Исходники: `src/main/kotlin/ru/sber/smartapp/dsl/`

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
| Компиляция | `./gradlew compileKotlin` |
| Тесты | `./gradlew test` |
| Сборка плагина (zip) | `./gradlew buildPlugin` → `build/distributions/smartapp-dsl-<version>.zip` |
| Запуск sandbox-IDE | `./gradlew runIde` |
| Проверка совместимости | `./gradlew verifyPlugin` |
| Регенерация словаря | `python tools/generate_keywords.py [path-to-resources__init__.py]` |
| Словарь из другого IDE | `./gradlew … -PlocalIdePath="/path/to/IDE.app"` |

**Деплой / установка:** собрать `buildPlugin`, затем в IDE
Settings → Plugins → ⚙ → *Install Plugin from Disk…* и выбрать zip из
`build/distributions/`. Публикация в Marketplace в v1 не настроена.

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
build.gradle.kts, settings.gradle.kts, gradle.properties   # сборка
gradle/wrapper/, gradlew                                    # Gradle wrapper (9.0)
tools/generate_keywords.py, tools/vendor/                   # генератор словаря
src/main/kotlin/ru/sber/smartapp/dsl/                       # исходники плагина
src/main/resources/META-INF/plugin.xml                      # дескриптор
src/main/resources/keywords/keywords.json                  # сгенерированный словарь
src/test/kotlin/ru/sber/smartapp/dsl/                       # тесты
docs/plans/, docs/insights/, arch/, mds/                    # материалы для AI-агентов
```

Игнорируется (`.gitignore`): `.gradle/`, `build/`, `.tooling/`, `*.iml`,
`.idea/`, `out/`, `.intellijPlatform/`.

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
  `curl` к API.
- `localIdePath` в `gradle.properties` указывает на локальную IDE; переопределяется
  флагом `-PlocalIdePath=…`.

## Стиль кода

- **Docstrings и комментарии — на русском языке.**
- **Планы, инсайты и документы — на русском** (`docs/plans/`, `docs/insights/`,
  `arch/`, `mds/`).
- **Сообщения в логах — на английском.**
- Комментарии пишутся только когда неочевидно «почему»; не дублировать «что»
  делает код.
- Kotlin: следовать официальным конвенциям; явные модификаторы видимости там,
  где это улучшает читаемость API.
