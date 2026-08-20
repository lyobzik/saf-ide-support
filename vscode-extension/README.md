# SmartApp DSL для VS Code

Расширение понимает DSL-сценарии фреймворка **Sber SmartApp Framework** —
JSON-файлы в каталоге `static/references/` (подкаталоги `scenarios/`, `forms/`,
`actions/`, `behaviors/`, `field_fillers/`, `classifiers/`) — и помогает с
навигацией и редактированием.

Это вторая реализация той же семантики: первая — плагин для IntelliJ-платформы
(каталог `idea-plugin/` того же репозитория). Обе стороны держатся на общих контрактах из `shared/`
(данные) и на общем корпусе `shared/fixtures` (поведение).

## Возможности

- **Подсветка** ключевых слов (значений `type`), структурных ключей и разметки
  Jinja — через семантические токены.
- **Переход к определению** (F12 / Ctrl+Click) для ссылок между файлами и для
  полей формы в `{{ main_form.<field> }}`.
- **Поиск использований** (Shift+F12) для определений и полей форм.
- **Автодополнение** значений `type` (по категории контейнера), имён сущностей в
  ссылочных позициях и имён полей после `{{ main_form.`.
- **Предупреждения** о неразрешённых ссылках и полях (severity Warning: целевая
  сущность может быть ещё не создана).
- **Переименование** (F2) определения или поля вместе со всеми использованиями.

Своего языка расширение не вводит — работает поверх встроенного JSON.

## Установка

```bash
npm install
npm run compile
npx @vscode/vsce package
```

Готовый `smartapp-dsl-<версия>.vsix` ставится через
`code --install-extension smartapp-dsl-<версия>.vsix` либо через
**Extensions → … → Install from VSIX…**.

Для разработки удобнее запустить Extension Development Host:

```bash
code --extensionDevelopmentPath=$PWD <путь-к-проекту-со-static/references>
```

## Разработка

| Действие | Команда |
|---|---|
| Проверка контрактов | `npm run verify:contract` |
| Сборка бандла | `npm run compile` |
| Unit + conformance | `npm run test:unit` |
| Интеграционные тесты в VS Code | `npm run test:integration` |
| Линтер | `npm run lint` |

## Устройство

```text
src/core/     # семантика без единого импорта vscode: индекс, резолв, лексер Jinja
src/vscode/   # адаптер: findFiles, watcher, провайдеры, конвертация смещений
test/core/    # тесты ядра
test/vscode/  # тесты адаптера на фейковом vscode
test/conformance/  # прогон общего корпуса shared/fixtures
test/integration/  # smoke в настоящем VS Code
```

Граница между `core` и `vscode` — не соглашение, а проверяемое правило:
eslint запрещает импорт `vscode` и файловой системы внутри `src/core/**`, а
отдельный `tsconfig.core.json` собирает ядро вовсе без типов редактора. Ядро
получает содержимое файлов снимками текста через `upsert`/`remove` и ничего не
знает о воркспейсе.

Все DSL-имена (виды сущностей, каталоги, сегменты пути, структурные ключи,
`main_form`) приходят из `shared/rules/rules.json` через единственный модуль
`src/core/contract.ts`. Литералов вроде `"scenarios"` или `"main_form"` в коде
быть не должно: контракт генерируется из Kotlin-таблиц плагина, и если имена
разъедутся, это поймает проверка контракта, а не пользователь.
