# `doHighlighting()` виснет в `runInDumbModeSynchronously`

**Контекст.** При написании `SmartAppHighlightingTest.testStructuralKeyHighlightWorksInDumbMode`
(план `2026-07-25-test-gaps-and-readme-fix`, H5) выяснилось: вызов
`myFixture.doHighlighting()` **внутри** `DumbModeTestUtils.runInDumbModeSynchronously { … }`
подвешивает тест намертво (превышение 3-минутного таймаута). Перенос
`myFixture.openFileInEditor(...)` наружу из dumb-блока не помог — виснет именно
`doHighlighting()`.

**Причина.** `CodeInsightTestFixture.doHighlighting()` форсирует полный daemon-pass
аннотаторов/инспекторов, который под капотом ждёт завершения индексации. Внутри
синхронного dumb-блока индексация «заморожена», и daemon не может прогрессировать —
возникает взаимная блокировка (тест ждёт daemon, daemon ждёт выхода из dumb mode).

**Контраст с существующим dumb-тестом.** `SmartAppDslTest.testDumbModeResolveSilentButKeywordsStillComplete`
работает, потому что в dumb-блоке вызывает только **чистые PSI-операции**:
`literal.references` → `SmartAppReference.multiResolve` (ранний `return` под guard'ом
`DumbService.isDumb`) и `myFixture.completeBasic()` (completion — отдельный pipeline,
не daemon-pass). `doHighlighting` к этой категории **не относится**.

**Решение в H5 (`testAnnotatorIsDumbAwareStaticContract`).** Прямой прогон
подсветки в dumb-блоке невозможен, поэтому H5 сознательно оформлен как
**статический контракт**, а не функциональный тест: он проверяет только маркер
`DumbAware` на `SmartAppAnnotator`. Это даёт гарантию, что платформа будет вызывать
аннотатор в dumb mode, а чисто-PSI ветка структурных ключей (без чтения индекса)
выполнится. Сама корректность FIELD проверяется в H1–H4 в обычном режиме.

**Важно:** статический контракт НЕ ловит регрессию вида «будущая правка добавила
индексное чтение перед structural-веткой» (тогда аннотатор по-прежнему `DumbAware`,
но structural-подсветка молчала бы в dumb mode из-за guard'а). Если такая защита
станет нужна, завести отдельный test seam — прямой вызов `SmartAppAnnotator.annotate(...)`
на hand-made `AnnotationHolder` без daemon-pass. В рамках Плана 1 это признано
избыточным: structural-ветка по построению не читает индекс.

**Правило для будущих тестов.** Не вызывать `myFixture.doHighlighting()` (и вообще
API, форсирующее daemon-pass: `findUsages`, инспекции) внутри
`runInDumbModeSynchronously`. Для dumb-mode-проверок ограничиваться прямыми PSI/
reference/completion-вызовами либо статическими контрактами (маркер `DumbAware`,
ранний guard).
