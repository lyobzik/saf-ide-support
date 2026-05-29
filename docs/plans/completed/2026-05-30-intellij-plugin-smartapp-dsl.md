> **Статус:** реализован (план перенесён в `completed/`).
>
> **Отклонения от плана при реализации** (для истории):
> - Индексатор использует JSON PSI `JsonObject.getPropertyList()` вместо
>   `LighterAST` — он так же сохраняет дубликаты top-level ключей, но проще и без
>   непроверенного платформенного предположения о доступности `lighterAST`.
> - Маркер-интерфейс `PsiDependentIndex` не использовался (отсутствует в целевой
>   платформе); индексатор опирается на `FileContent.psiFile`.
> - Тулчейн уточнён: IntelliJ Platform Gradle Plugin **2.16.0** (требует
>   Gradle **9.0+**), JVM target **17**, gson бандлится в `lib/`,
>   `instrumentCode = false`.
> - Gradle-таска `generateKeywords` не оформлялась: словарь генерируется ручным
>   запуском `tools/generate_keywords.py`.

# IntelliJ IDEA плагин для DSL сценариев SmartApp Framework

## Context

`smart_app_framework` (Sber, Python) описывает диалоговые сценарии не отдельным
текстовым языком, а набором **JSON-файлов** в каталоге `static/references/`:

| Подкаталог | Верхнеуровневые ключи = | Пример ссылок наружу |
|---|---|---|
| `scenarios/*.json` | имя сценария | `form` → form, `run_scenario.scenario` & `scenario_description` → scenario |
| `forms/*.json` | имя формы | поле `filler(external).filler` → filler, `classifier(external).classifier` → classifier |
| `actions/*.json` | имя action | `action(external).action` → action |
| `behaviors/*.json` | имя behavior | `success_action/fail_action/timeout_action(external).action` → action |
| `field_fillers/*.json` | имя filler | — |
| `classifiers/*.json` | имя classifier | — |

«Ключевые слова» DSL — это значения поля `type` (и нескольких структурных
ключей). Полный, авторитетный список регистрируется в исходниках фреймворка в
`smart_kit/resources/__init__.py` методами `init_requirements`, `init_actions`,
`init_field_filler_description`, `init_field_requirements`, `init_scenarios`,
`init_form_descriptions`, `init_field_descriptions`, `init_classifiers` и т.д.
(пример: `form_filling`, `tree`, `base`, `composite`, `regexp`, `classifier`,
`external`, `approve`, `intersection`, `run_scenario`, `else`, `template`,
`requirement`, `question`, `integration`…).

**Задача:** плагин для IDEA, дающий по этим файлам (1) подсветку ключевых слов,
(2) переход к определению сущности, (3) поиск использований. Дополнительно —
автодополнение и подсветку неразрешённых ссылок.

**Подход (согласовано):** не вводим свой тип файла/лексер, а строим
**семантический слой поверх встроенного JSON-парсера IDEA**. Это сохраняет всю
штатную JSON-поддержку и резко сокращает объём кода. Язык — **Kotlin**, сборка —
**IntelliJ Platform Gradle Plugin 2.x**. Список ключевых слов **генерируется из
исходников фреймворка** в ресурс плагина.

## Архитектура плагина

Проект создаётся в текущем пустом каталоге `/Users/zaa/work/nlpf-idea`.

```
build.gradle.kts, settings.gradle.kts, gradle.properties + gradle wrapper
tools/generate_keywords.py            # генератор словаря ключевых слов
src/main/resources/META-INF/plugin.xml
src/main/resources/keywords/keywords.json   # сгенерированный словарь (по категориям)
src/main/kotlin/ru/sber/smartapp/dsl/
  SmartAppRefKind.kt            # enum: SCENARIO, FORM, ACTION, BEHAVIOR, FILLER, CLASSIFIER
  SmartAppFiles.kt              # детектор: к какому kind относится JSON-файл (по пути static/references/<kind>/)
  SmartAppKeywords.kt           # загрузка keywords.json, доступ по категориям
  highlight/SmartAppTextAttributes.kt
  highlight/SmartAppColorSettingsPage.kt
  annotator/SmartAppAnnotator.kt
  index/SmartAppDefinitionIndex.kt
  reference/SmartAppReferenceContributor.kt
  reference/SmartAppReference.kt
  completion/SmartAppCompletionContributor.kt
  findusages/SmartAppFindUsagesProvider.kt
  findusages/SmartAppElementDescriptionProvider.kt
src/test/...                    # тесты на BasePlatformTestCase + ресурсы-фикстуры
```

**Зависимость от JSON-плагина (две стороны контракта).** Недостаточно объявить её
только в `plugin.xml` — для компиляции и sandbox-тестов Gradle 2.x требует и
build-зависимость:
- `plugin.xml`: `<depends>com.intellij.modules.platform</depends>` +
  `<depends>com.intellij.modules.json</depends>`;
- `build.gradle.kts`: `dependencies { intellijPlatform { bundledPlugin("com.intellij.modules.json") } }`.

Без Gradle-зависимости `JsonProperty`/`JsonStringLiteral` не резолвятся в тестах/
IDE-sandbox (см. JetBrains «Plugin Dependencies»).

### Совместимость с индексами и Dumb Mode (общая политика)
Доступ к `FileBasedIndex` запрещён во время индексирования (dumb mode). Поэтому:
- любой обработчик, читающий индекс (resolve ссылок, completion имён, проверка
  unresolved), оборачивается guard'ом `if (DumbService.isDumb(project)) return`;
- в dumb mode подсветка unresolved-ссылок **пропускается** (не ошибка);
- `SmartAppAnnotator` помечается `DumbAware` — реализации с `DumbAware`
  **вызываются** и во время индексации, поэтому подсветка ключевых слов/
  структурных ключей (чисто по PSI, без индекса) работает даже в dumb mode.
  Index-зависимая ветка («битые ссылки») внутри `annotate()` гасится guard'ом
  `if (DumbService.isDumb(project)) return` и доисполняется после индексации, когда
  платформа перезапустит daemon.
- `SmartAppCompletionContributor`: помечается `DumbAware` **только** если все
  чтения индекса обёрнуты guard'ом `DumbService.isDumb(project)` (тогда в dumb
  mode он отдаёт лишь не-индексные варианты, например ключевые слова `type`); иначе
  `DumbAware` не ставится и платформа просто не вызывает contributor до конца
  индексации. Выбираем первый вариант.

### 1. Определение «наших» файлов — `SmartAppFiles` + `SmartAppRefKind`
Файл считается DSL-файлом сценариев, если выполнены **все** условия (без
substring-проверок пути):
- расширение `.json` (`VirtualFile.extension`);
- сегменты пути через `VfsUtilCore.getRelativePath`/обход родителей содержат
  подряд `static` → `references` → `<kind>`, где `<kind>` ∈ {scenarios, forms,
  actions, behaviors, field_fillers, classifiers}; допускаются произвольные
  вложенные директории **после** `<kind>` (например `scenarios/sub/a.json`);
- корень документа — JSON-объект.

Утилита возвращает `SmartAppRefKind?` по `VirtualFile`/`PsiFile`. Path-check дешёвый, поэтому
в v1 **не кэшируем** (проще и без риска устаревания). Если профайлинг покажет
горячую точку — кэш на `VirtualFile.userData` хранится **вместе с `file.url`** и
пересчитывается при несовпадении url (т.к. `VirtualFile` переживает move/rename и
голый `UserData` сам не инвалидируется). Все расширения работают по JSON-PSI, но в
начале каждого обработчика стоит быстрый guard на `SmartAppRefKind != null`.

### 2. Словарь ключевых слов — `tools/generate_keywords.py` + `SmartAppKeywords`
Скрипт принимает путь к исходникам фреймворка (или vendored-копии
`smart_kit/resources/__init__.py`) и **парсит AST** (`ast` модуль), а не регэксп —
regex пропускает `dict.update({...})`, одинарные кавычки, multiline и alias-словари.
Извлекаем внутри методов `init_*`:
- `ast.Assign`/`ast.AnnAssign` с целью `ast.Subscript` вида `<dict>["<kw>"] = Class`;
- `dict.update({...})` (узлы `ast.Call` с атрибутом `update`).
Каждый ключ относим к категории по имени словаря (`requirements`, `actions`,
`field_filler_description`, `field_requirements`, `scenarios`,
`form_descriptions`, `field_descriptions`, classifiers, operators, comparators).
Результат — `keywords.json` с метаданными: `source_file`, `source_sha256`,
`generated_at`, и `counts` по категориям (для контроля дрейфа). Подключается как
Gradle-таска `generateKeywords` (ручной запуск при обновлении фреймворка).
`SmartAppKeywords` лениво читает ресурс и даёт `isKeyword(category, value)` и
`all(category)`.

### 3. Подсветка ключевых слов — `SmartAppAnnotator` (EP `com.intellij.annotator`, language=JSON)
Для `JsonProperty` с именем `type`, чьё строковое значение есть в словаре, красит
значение ключом `SMARTAPP_KEYWORD`. Структурные ключи (`form`, `filler`,
`classifier`, `actions`, `requirement`, `fields`, `questions`,
`on_filled_actions`, `scenario`, `scenario_description`…) красятся ключом
`SMARTAPP_FIELD`. Цвета регистрируются через `SmartAppTextAttributes` +
`SmartAppColorSettingsPage` (страница «SmartApp DSL» в Settings → Color Scheme),
поэтому пользователь может их настраивать. Цвета по умолчанию наследуются
от штатных `DefaultLanguageHighlighterColors.KEYWORD` (ключевые слова) и
`.INSTANCE_FIELD`/`.KEYWORD` для структурных ключей, чтобы вписаться в любую тему.
В этом же аннотаторе неразрешённые ссылки помечаются через актуальный API
`holder.newAnnotation(HighlightSeverity.WARNING, …).range(…).create()`. Уровень —
**WARNING**, а не ERROR: целевая форма/экшен может быть ещё не создана в процессе
работы, и красные ошибки раздражали бы. Jinja-значения сюда не попадают (см.
Jinja-guard).

### 4. Индекс определений — `SmartAppDefinitionIndex`
Это `FileBasedIndexExtension<String, SmartAppDefinitionValue>` (**не**
`ScalarIndexExtension` — нам нужно value со смещением). **Составной ключ**
`"<kind>:<name>"` (а не голое `name`) — тогда resolve идёт точным запросом без
вычитки всех одноимённых сущностей других kind; для правила `action` делаем два
запроса (`ACTION:<name>` и `BEHAVIOR:<name>`). Value =
`SmartAppDefinitionValue(offsets: List<Int>)` с собственным `DataExternalizer`
(пишем длину + offsets через `DataInputOutputUtil.writeINT`/var-int; класс несёт
`SERIALIZATION_VERSION`, входящий в `getVersion()`). **Список offsets**, а не один
— потому что `DataIndexer.map()` возвращает `Map<key, value>`, и при двух
определениях с одним `name` в **одном файле** скаляр перетёрся бы; список
сохраняет оба (питает тест «дубликаты в одном файле»). Объединять offsets надо в
момент `map()` (сгруппировать ключи внутри файла перед возвратом).

Имена **полей форм в v1 НЕ индексируются** и не резолвятся: в `SmartAppRefKind`
нет `FIELD`, ссылок на поля (они появляются внутри Jinja `{{ main_form.x }}`)
в первой версии нет. Индексируются только верхнеуровневые ключи каждого файла как
сущности соответствующего kind. Поддержка полей (отдельный `FIELD`-contract +
парсинг Jinja) — явный пункт будущих версий, чтобы не плодить ложные
definitions/completion.

**Версионирование.** `getVersion()` фиксируется и **увеличивается** при любом
изменении формата сериализации `SmartAppDefinitionValue` (введение `offsets:
List<Int>`, смена `DataExternalizer` и т.п.), иначе IDE прочитает старый индекс
несовместимо.

**Indexer — content-only, project-free, duplicate-preserving.**
`DataIndexer.map(FileContent)` зависит только от `FileContent`: путь проверяется
по `FileContent.file` (input filter + сегменты пути). JSON разбирается **способом,
сохраняющим повторяющиеся top-level ключи**, — через IntelliJ `LighterAST`
(`FileContent.lighterAST`, JSON-парсер платформы), а не через object-model парсер
(Gson/Jackson/`JsonObject`), который схлопнул бы дубликаты до группировки. Обходим
члены корневого объекта, для каждого ключа берём offset, группируем одинаковые
имена в `offsets`. Indexer не читает PSI проекта, ресурс keywords, настройки или
соседние файлы. `map()` обёрнут в try/catch → при невалидном JSON возвращает
пустой `Map`, не ломая индексацию проекта.

**Day-1 проверка платформенного контракта:** в sandbox убедиться, что
`FileContent.lighterAST` для JSON в целевой `sinceBuild` доступен и содержит узлы
объекта/свойств. Если для какой-то версии JSON-плагина LighterAST окажется
недоступен/неполон — fallback на PSI-индексацию через `FileContent.psiFile`
(дороже, но рабочее). Это единственное место плана с непроверенным платформенным
предположением.

Сохраняемый offset — это offset **токена имени свойства в `LighterAST`** (внутри
indexer, без PSI), а не начало узла свойства, чтобы навигация ставила каретку на
имя, а не на кавычку/начало узла. При resolve (уже через PSI) от этого offset
поднимаемся к ближайшему `JsonProperty`.

**Контракт резолва.** Resolver не использует `getValues` в отрыве от файла: он
идёт через `FileBasedIndex.getContainingFiles`/`processValues` по точному
составному ключу `"<kind>:<name>"` в нужном скоупе, получает пары
`(VirtualFile, SmartAppDefinitionValue)`, берёт `PsiFile` и по каждому offset из
`value.offsets` восстанавливает элемент **надёжно**:
`PsiTreeUtil.findElementOfClassAtOffset(psiFile, offset, JsonStringLiteral, true)`
→ его `parent as? JsonProperty` (а не сырой `findElementAt`, который попадёт на
кавычку/пробел). Дополнительно **валидируем**, что это именно определение —
ключ верхнего уровня (его `JsonProperty.parent.parent` — корневой `JsonObject`/
`PsiFile`), а не одноимённое вложенное свойство-значение.

**Известное ограничение JSON PSI:** при дубликатах top-level ключей индекс через
`LighterAST` честно хранит оба offset, но `JsonObject.findProperty(name)` отдаёт
один. Поэтому навигация строится по сохранённым offset (оба видны в
`multiResolve`), а не через `findProperty`. Принимается как данность; покрыто
тестом `multiResolve().size == 2`. Используется
резолвом ссылок, автодополнением и поиском без сканирования всех файлов.

### 5. Переход к определению — `SmartAppReferenceContributor` + `SmartAppReference`
Правила ссылок задаются **замороженной таблицей** (frozen rule table), а не
ad-hoc эвристиками; каждое правило — `(условие на узел) → target kind(s)`. Условие
проверяет имя свойства И, где нужно, значение соседнего `type` И, где нужно, kind
самого файла, чтобы избежать ложных совпадений (`scenario`/`action`/`classifier`/
`filler` встречаются в разных вложенных объектах):

| Условие (значение строки в позиции) | Target kind |
|---|---|
| свойство `form` (в файле scenarios) | FORM |
| свойство `scenario`, у объекта-владельца `type == run_scenario` | SCENARIO |
| свойство `scenario_description` | SCENARIO |
| свойство `filler`, у объекта-владельца `type == external` | FILLER |
| свойство `classifier`, у объекта-владельца `type == external` | CLASSIFIER |
| свойство `action`, у объекта-владельца `type == external` | ACTION, BEHAVIOR |

`SmartAppReferenceContributor` (language=JSON) вешает `PsiReferenceProvider` на
`JsonStringLiteral` через `PsiElementPattern`, реализующие эти условия.
`SmartAppReference : PsiPolyVariantReferenceBase` резолвит через индекс во все
подходящие `JsonProperty` (он же `PsiNamedElement`).

**Jinja-guard.** Если текст строкового значения содержит `{{` или `{%` (Jinja2-
шаблон фреймворка), ссылка **не создаётся** вовсе (ранний `return null` в
provider). Тогда такие значения не попадают ни в resolve, ни в проверку
unresolved — и не подсвечиваются как ошибка. Покрывается значениями как
целиком-шаблонными (`"{{ x }}"`), так и смешанными (`"a_{{ x }}_b"`).

**Политика мульти-kind/коллизий.** Для правила `action` цель — ACTION **или**
BEHAVIOR; `multiResolve` возвращает все определения с этим именем в обоих kind.
Поведение при коллизиях фиксируется тестами: (а) имя есть в обоих kind →
несколько результатов; (б) имя есть только в одном; (в) имени нет нигде →
unresolved. Go-to-definition/Ctrl-click работают штатно по `resolve()`.

### 6. Поиск использований — `SmartAppFindUsagesProvider` + ElementDescriptionProvider
Определения — это `JsonProperty` (уже `PsiNamedElement`). `FindUsagesProvider`
(для JSON, с guard на наши файлы) включает действие Find Usages и задаёт тип/имя
сущности. Поскольку текст в местах использования буквально совпадает с именем,
штатный `ReferencesSearch` (по word-индексу + наши `PsiReference`) находит все
использования. `ElementDescriptionProvider` даёт человекочитаемые подписи
(«scenario hello_scenario», «form hello_form») в окне Find Usages.

### 7. Автодополнение — `SmartAppCompletionContributor`
- каретка в значении `type` → подсказываем ключевые слова категории контейнера.
  **Эвристика категории** (формализована, как и таблица ссылок): от позиции
  поднимаемся к ближайшему `JsonObject`-владельцу, смотрим имя свойства, под
  которым он лежит (`filler`→filler, `classifier`→classifier, `action`→action,
  `questions[*]`/корень action→action, `fields.*`→field_description, `requirement`
  →requirement, корневой объект scenarios-файла→scenario и т.д.); если владелец не
  опознан — поднимаемся выше; на root без совпадения предлагаем по kind файла;
- каретка в значении ссылочного ключа (`form`, `scenario`, `filler`,
  `classifier`, `action`) → подсказываем имена сущностей нужного kind из индекса.
- варианты — `PrioritizedLookupElement`: ключевые слова `type` выше имён
  сущностей, если контексты пересеклись; чтение индекса — под dumb-guard.

## Ключевые повторно используемые API платформы
- JSON PSI: `JsonProperty`, `JsonStringLiteral`, `JsonObject` (бандл-плагин JSON).
- `com.intellij.psi.PsiReferenceContributor` / `PsiPolyVariantReferenceBase`.
- `com.intellij.util.indexing.FileBasedIndexExtension<String, SmartAppDefinitionValue>`
  (со своими `KeyDescriptor` и `DataExternalizer`; **не** `ScalarIndexExtension`,
  т.к. нужен value со смещением).
- `com.intellij.lang.annotation.Annotator` + `TextAttributesKey` /
  `ColorSettingsPage`.
- `com.intellij.lang.findUsages.FindUsagesProvider`,
  `ElementDescriptionProvider`; опционально `UsageTypeProvider` для группировки
  использований («form reference», «scenario reference», «external action
  reference»).
- `com.intellij.codeInsight.completion.CompletionContributor`.

## Проверка (verification)
1. **Юнит/интеграционные тесты** (`./gradlew test`, `BasePlatformTestCase`):
   - фикстуры — мини-`static/references/` (копии `hello_scenario.json`,
     `hello_form.json`, `field_fillers.json`, `classifiers.json`);
   - resolve: `form: "hello_form"` резолвится в определение формы; `run_scenario`
     → сценарий; `filler(external)` → filler; `classifier(external)` → classifier;
   - find usages: на определении `hello_form` находит ссылку из сценария;
   - highlighting: `SMARTAPP_KEYWORD` стоит на значениях `type`, **WARNING** — на
     несуществующем имени формы (severity совпадает с реализацией);
   - completion: в позиции `type` и в значении `form` есть ожидаемые варианты;
   - **негативные/IDE-специфичные кейсы:** dumb mode (индекс недоступен — resolve
     и unresolved-проверка молчат без исключений; completion **раздваивается**:
     дополнение имён сущностей из индекса молчит, а дополнение ключевых слов
     `type` (не-индексное) работает — отдельные проверки); невалидный JSON; дубликаты
     определений в одном файле (JSON с двумя одинаковыми top-level ключами →
     `multiResolve().size == 2`, цементирует duplicate-preserving `LighterAST`-
     обход); отдельный unit-тест на `LighterAST`-offset: сохранённый offset при
     `findElementAt(offset)` попадает внутрь имени свойства и поднимается к нужному
     `JsonProperty`; одно имя в разных файлах; одно имя в разных kind
     (action vs behavior); ссылки/файлы вне `static/references/` (не активны);
     completion вне DSL-файлов (молчит); динамические значения с Jinja
     (`{{ ... }}`) не считаются битой ссылкой; разные разделители пути.
2. **Ручная проверка в IDE** (`./gradlew runIde`): открыть проект с
   `static/references/` (взять шаблон из репозитория фреймворка), убедиться, что
   ключевые слова подсвечены, Ctrl-click по `form`/`scenario` ведёт к определению,
   Alt+F7 на имени формы показывает использования, автодополнение работает, битая
   ссылка подсвечена как warning (не error-level).
3. **Генератор словаря**: запустить `python tools/generate_keywords.py <path-to-framework>`
   и сверить `keywords.json` с актуальным `smart_kit/resources/__init__.py`.

## Замечания
- Каталог `/Users/zaa/work/nlpf-idea` сейчас не под git — на старте имеет смысл
  `git init` (по желанию пользователя).
- Версии платформы: `sinceBuild` фиксируется в `gradle.properties` (целимся в
  IDEA 2023.3+, т.е. `233`), `untilBuild` не задаём (открытая верхняя граница).
  JSON-плагин входит и в Community, и в Ultimate (и в PyCharm/др. — DSL-поддержка
  будет доступна там же, где есть JSON-плагин).

## Future work (вне v1)
- **Поля форм:** отдельный `FIELD`-contract + парсинг Jinja `{{ main_form.x }}`
  для перехода/поиска по именам полей. Индексатор расширяется на вложенные ключи.
- **JSON Schema provider** (`JsonSchemaProviderFactory`) для структурной валидации
  (обязательные поля под каждый `type`, типы значений) — дополняет семантический
  слой.
- **Интеграционная проверка на реальных данных** из `static/references/` шаблона
  фреймворка для отлова edge-cases сверх мини-фикстур.
