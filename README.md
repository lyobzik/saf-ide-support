# SmartApp DSL — поддержка в IntelliJ IDEA и VS Code

Репозиторий содержит **две** реализации поддержки одного языка: плагин для
IntelliJ-платформы (`idea-plugin/`) и расширение для VS Code
(`vscode-extension/`). Обе понимают одну и ту же семантику и держатся вместе
общими контрактами из `shared/` — подробнее в разделе «Монорепозиторий».

Плагин добавляет поддержку DSL-сценариев фреймворка **Sber SmartApp Framework**
(`smart_app_framework`) прямо в IDE. Сценарии этого фреймворка описываются не
отдельным языком, а **JSON-файлами** в каталоге `static/references/` (подкаталоги
`scenarios/`, `forms/`, `actions/`, `behaviors/`, `field_fillers/`,
`classifiers/`). Плагин понимает их семантику и помогает с навигацией и
редактированием.

## Возможности

- **Подсветка** ключевых слов (значения поля `type`) и структурных ключей.
- **Переход к определению** сущности — Go to Definition / Ctrl+Click (⌘+Click).
- **Поиск использований** — Find Usages / Alt+F7.
- **Автодополнение** значений `type` и ссылочных ключей (`form`, `scenario`,
  `filler`, `classifier`, `action`).
- **Предупреждения** о неразрешённых ссылках (severity WARNING — не ошибка, так
  как целевая сущность может быть ещё не создана).
- **Поля форм в Jinja** — навигация, автодополнение и подсветка разметки в
  выражениях `{{ main_form.<field> }}` и `{% … %}` (подробнее ниже).
- **Переход к файлу шаблона** — `"file"` при `"type": "unified_template"` ведёт
  в `static/references/templates/<значение>`.

Ни плагин, ни расширение не вводят собственный язык или лексер: оба строят
семантический слой поверх встроенного JSON-парсера редактора. Плагин работает в
любой IDE на платформе IntelliJ, где есть бандл-плагин JSON (IDEA
Community/Ultimate, PyCharm, GIGA IDE и др.); расширение — в VS Code 1.90+.

## Поля форм в Jinja-значениях

Строковые значения DSL часто содержат Jinja2-шаблоны. Внутри выражений Jinja —
и в интерполяциях `{{ … }}`, и в statement-тегах `{% … %}` — плагин понимает
обращения к полям формы:

```json
"greeting": "{{ main_form.name }}"
```

- **Go to Definition / Find Usages** для имени поля — ведут на определение поля
  в `forms.<форма>.fields.<поле>`. Целевая форма в сценарии берётся из свойства
  `form` того же сценария, а внутри файла формы `main_form` — это сама форма,
  в определении которой стоит значение.
- **Переход с самой переменной `main_form`** — на определение целевой формы.
  Имени формы в этом месте не написано, поэтому переименование формы `main_form`
  не переписывает и в поиск использований формы он не попадает.
- **Автодополнение** имён полей после `{{ main_form.` — из индекса полей
  целевой формы (предлагаются только имена-идентификаторы).
- **Подсветка разметки** Jinja: разделители, переменные, `.`, фильтры (`|` и имя),
  строковые литералы — настраивается в Settings → Color Scheme → SmartApp DSL.
- **WARNING** «Не удаётся разрешить поле» — только когда форма известна
  (статическая строка в `form`), а поля в ней нет. Динамический выбор формы
  (`"form": "{{ … }}"`) предупреждений не даёт.

Осознанные ограничения текущей версии: у цепочки `main_form.a.b` семантику
получает только первый сегмент — что такое `b`, знать неоткуда, схемы значений
полей не существует; `main_form` в позиции чужого поля (`variables.main_form.x`)
семантики не получает вовсе; поля, чьи имена не являются идентификаторами
(содержат `.`, `:`, `-`), в автодополнении не предлагаются. Jinja работает и в
строковых элементах JSON-массивов, не только в значениях свойств.

## Шаблоны в отдельных файлах

Значение `"file"` в объекте `"type": "unified_template"` называет файл шаблона:

```json
"items": { "type": "unified_template", "file": "items.jinja2", "loader": "json" }
```

Такое значение — ссылка на файл `static/references/templates/items.jinja2` того
же набора: работает переход, а отсутствующий файл даёт WARNING. Значение
содержит имя файла вместе с расширением; вложенные пути (`nested/items.jinja2`)
допускаются, а выход за пределы каталога (`../…`) целью не считается и ошибкой
не помечается.

Правило выведено из раскладки эталонного приложения, а не из исходников
`smart_app_framework`: ключа `file` в публичных исходниках фреймворка нет.

**Подсветка внутри самих `.jinja2`-файлов не входит в задачу плагина.** Обе
реализации — семантический слой поверх JSON-парсера редактора, отдельный язык
они не вводят; раскрасить сам шаблон помогут специализированные средства (в
VS Code — любое расширение для Jinja, в IDE на платформе IntelliJ — плагин с
поддержкой Jinja2). Переход в файл при этом работает независимо от них.

## Монорепозиторий: две реализации и общий контракт

```text
idea-plugin/        # плагин для IntelliJ-платформы (Kotlin, Gradle)
vscode-extension/   # расширение для VS Code (TypeScript, npm)
shared/
  rules/rules.json      # контракт данных: виды, каталоги, правила ссылок, ключи
  keywords/keywords.json  # словарь ключевых слов фреймворка
  fixtures/             # conformance-корпус: общий для обеих реализаций
tools/                # генератор словаря
```

Системы сборки намеренно не объединены: Gradle собирает плагин, npm — расширение.
Вместе их держат два контракта:

- **данные** — `shared/rules/rules.json` генерируется из Kotlin-таблиц плагина
  (`./gradlew :idea-plugin:exportRules`) и читается расширением; править его
  руками нельзя, `exportRules --check` следит за актуальностью снимка;
- **поведение** — `shared/fixtures/` прогоняются и тестами плагина
  (`SmartAppConformanceTest`), и тестами расширения. Расхождение алгоритмов
  ловится только так, поэтому новая семантика без нового кейса не принимается.

Оба контракта проверяются в CI (`.gitlab-ci.yml`, стадия `contract`).

## Установка

Ни плагин, ни расширение пока не публикуются в Marketplace — обе стороны
ставятся из файла. Оба артефакта собираются одной командой в каталог `dist/`:

```bash
tools/package.sh
```

```text
dist/smartapp-dsl-<версия>.zip     # плагин для IntelliJ-платформы
dist/smartapp-dsl-<версия>.vsix    # расширение для VS Code
```

Готовые артефакты можно взять из раздела релизов репозитория или из артефактов
джоб `package:idea` и `package:vscode` в CI: по тегу вида `v<версия>` они
собираются автоматически, на ветках — по кнопке. Версия в теге обязана совпадать
с версией в манифестах, иначе пайплайн падает до упаковки; на теге другого вида
упаковки не будет и по кнопке.

### Плагин для IDE на IntelliJ-платформе

1. **Settings/Preferences → Plugins**.
2. Иконка шестерёнки ⚙ → **Install Plugin from Disk…**.
3. Выберите ZIP-файл.
4. Перезапустите IDE по запросу.

### Расширение для VS Code

```bash
code --install-extension dist/smartapp-dsl-<версия>.vsix
```

Либо **Extensions → … → Install from VSIX…**.

После перезапуска откройте проект, содержащий каталог `static/references/…`, —
подсветка, переход к определению, поиск использований и автодополнение заработают
в JSON-файлах внутри этого каталога.

> Минимальная версия платформы: build **251** (IDEA 2025.1) и новее.

## Сборка из исходников

> **Важно:** в системе может не быть отдельного JDK. Перед любой `gradle`-командой
> экспортируйте `JAVA_HOME` на JBR из локальной IDE:
>
> ```bash
> export JAVA_HOME="/Applications/GIGA IDE CE 2025.1.app/Contents/jbr/Contents/Home"
> ```

| Действие | Команда |
|---|---|
| Компиляция | `./gradlew :idea-plugin:compileKotlin` |
| Тесты | `./gradlew :idea-plugin:test` |
| Сборка ZIP-плагина | `./gradlew :idea-plugin:buildPlugin` → `idea-plugin/build/distributions/smartapp-dsl-<версия>.zip` |
| Упаковка плагина в `dist/` | `./gradlew :idea-plugin:packagePlugin` → `dist/smartapp-dsl-<версия>.zip` |
| Запуск sandbox-IDE | `./gradlew :idea-plugin:runIde` |
| Проверка совместимости | `./gradlew :idea-plugin:verifyPlugin` |
| Экспорт контракта данных | `./gradlew :idea-plugin:exportRules` |

По умолчанию плагин компилируется и запускается против **локальной** IDE на
платформе IntelliJ: путь задаётся параметром `localIdePath` в `gradle.properties`
(по умолчанию — GIGA IDE) и переопределяется флагом:

```bash
./gradlew :idea-plugin:buildPlugin -PlocalIdePath="/path/to/IDE.app"
```

Там, где локальной IDE нет (CI, чужая машина), источник платформы переключается
на maven-репозитории:

```bash
./gradlew :idea-plugin:test -PideSource=maven -PplatformVersion=2025.1
```

## Расширение для VS Code

```bash
npm --prefix vscode-extension install
npm --prefix vscode-extension test          # контракты + ядро + адаптер + корпус
npm --prefix vscode-extension run compile   # бандл out/extension.js
npm --prefix vscode-extension run package   # dist/smartapp-dsl-<версия>.vsix
```

Установка и разработка описаны в [vscode-extension/README.md](vscode-extension/README.md).

## Технологический стек

- **Kotlin** 2.0.21 (JVM target 21)
- **IntelliJ Platform Gradle Plugin** 2.16.0 (требует Gradle 9.0+)
- **Gradle** 9.0.0 (через wrapper, ставить отдельно не нужно)
- **JBR 21** из локальной IDE для компиляции и запуска
- бандл-плагин `com.intellij.modules.json` (JSON PSI)
- **gson** 2.11.0 — парсинг словаря ключевых слов
- **JUnit 4** + `BasePlatformTestCase` — тесты

## Словарь ключевых слов

Список «ключевых слов» (допустимые значения `type`) генерируется из исходников
фреймворка скриптом-генератором, который AST-парсит
`smart_kit/resources/__init__.py`:

```bash
python tools/generate_keywords.py [путь-к-resources__init__.py]
```

Результат — `shared/keywords/keywords.json` (общий для обеих реализаций; в
ресурсы плагина он копируется на этапе сборки). Режим `--check` проверяет, что
закоммиченный словарь соответствует vendored-исходнику. В репозитории лежит
vendored-копия исходника фреймворка (`tools/vendor/`), поэтому генератор можно
запускать без доступа к самому фреймворку. Перезапускайте генерацию при
обновлении версии фреймворка.

## Структура проекта

```
settings.gradle.kts, gradle.properties, gradlew   # сборка плагина (Gradle 9)
.gitlab-ci.yml                                    # CI: контракты + тесты обеих сторон
idea-plugin/build.gradle.kts                      # модуль плагина
idea-plugin/src/main/kotlin/ru/sber/smartapp/dsl/ # исходники плагина
idea-plugin/src/main/resources/META-INF/plugin.xml # дескриптор плагина
idea-plugin/src/test/kotlin/                      # тесты плагина
vscode-extension/src/core/                        # ядро расширения (без vscode API)
vscode-extension/src/vscode/                      # адаптер VS Code
vscode-extension/test/                            # тесты ядра, адаптера, корпуса, интеграции
shared/rules/, shared/keywords/, shared/fixtures/ # общие контракты
tools/generate_keywords.py, tools/vendor/         # генератор словаря
docs/plans/, docs/insights/, arch/, mds/          # материалы для AI-агентов
```

## Документация для разработчиков

Подробное руководство (архитектура, ключевые компоненты, паттерны платформы,
правила тестирования, формат коммитов, стиль кода) — в [AGENTS.md](AGENTS.md).
