package ru.sber.smartapp.dsl.findusages

import com.intellij.find.findUsages.CustomUsageSearcher
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.util.Processor
import ru.sber.smartapp.dsl.contract.ResourceScanSpec
import ru.sber.smartapp.dsl.resources.SmartAppCustomKeywords
import ru.sber.smartapp.dsl.resources.SmartAppResourceScanner

/**
 * Добавляет к поиску использований класса в Python-коде вхождения слов, под
 * которыми этот класс зарегистрирован в ресурсах приложения.
 *
 * Почему `CustomUsageSearcher`, а не [SmartAppFindUsagesHandlerFactory]:
 * handler у цели ровно один, и, перехватив класс, мы отняли бы у плагина Python
 * его собственный поиск — ссылки на класс в `.py` из списка исчезли бы. Этот же
 * EP **дополняет** результат, поэтому в одном окне оказываются и питоновские
 * использования класса, и строки DSL, где стоит его слово.
 *
 * Направление работает только там, где у `.py` есть настоящее PSI (установлен
 * плагин Python): без него под кареткой лежит файл целиком и класса не
 * существует. Автоматическим тестом это не покрывается — в тестовой платформе
 * плагина Python нет, — поэтому проверяется вручную при приёмке, как и остальные
 * случаи «каретка в `.py`».
 */
class SmartAppKeywordClassUsagesSearcher : CustomUsageSearcher() {

    override fun processElementUsages(
        element: PsiElement,
        processor: Processor<in Usage>,
        options: FindUsagesOptions,
    ) {
        if (!options.isUsages) return
        val targets = runReadAction { targetsFor(element) }
        for (target in targets) {
            // Вхождение заворачивается в адаптер там же, где создаётся, — под
            // read action обхода: `UsageInfo2UsageAdapter` читает PSI.
            val proceed = SmartAppCustomKeywordSearch(target).run(options) { usage: UsageInfo ->
                processor.process(UsageInfo2UsageAdapter(usage))
            }
            if (!proceed) return
        }
    }

    /**
     * Слова, зарегистрированные на элемент под кареткой. Пустой список — «это не
     * класс ресурсов», и поиск ничего не добавляет.
     */
    private fun targetsFor(element: PsiElement): List<SmartAppCustomKeywordTarget> {
        // Имя файла классом не является: у `PsiFile` тоже есть `name`.
        if (element is PsiFile) return emptyList()

        val named = element as? PsiNamedElement ?: return emptyList()
        val name = named.name ?: return emptyList()
        val file = element.containingFile ?: return emptyList()
        val virtualFile = file.virtualFile ?: return emptyList()
        if (!virtualFile.name.endsWith(ResourceScanSpec.fileExtension)) return emptyList()

        val appRoot = SmartAppCustomKeywords.ownerApplicationRoot(virtualFile) ?: return emptyList()
        // Класс из вендоренной зависимости приложению не принадлежит, хотя и
        // лежит внутри его каталога: тот же фильтр, что у обхода JSON.
        if (SmartAppCustomKeywords.hasExcludedSegment(virtualFile, appRoot)) return emptyList()

        val targets = SmartAppCustomKeywordTargets.ofClassName(element.project, appRoot, name)
        // Разбор файла — последним: он дороже словаря, а нужен только там, где
        // имя вообще совпало с зарегистрированным классом.
        if (targets.isEmpty() || !declaresClassAt(file, name, element.textRange)) return emptyList()
        return targets
    }

    /**
     * Объявляет ли [range] в файле [file] класс с именем [name].
     *
     * Совпадения имени мало: под кареткой может оказаться функция или
     * переменная, названная как зарегистрированный класс, — и вхождения из DSL
     * приехали бы к ней. Тип элемента напрямую не проверить: PSI Python нам
     * недоступно (плагина Python в зависимостях нет), поэтому спрашиваем у
     * собственного сканера, попадает ли в диапазон элемента имя объявленного
     * здесь класса. У `PyClass` диапазон покрывает объявление целиком, включая
     * декораторы, поэтому проверка именно на попадание, а не на начало.
     */
    private fun declaresClassAt(file: PsiFile, name: String, range: TextRange?): Boolean {
        if (range == null) return false
        return SmartAppResourceScanner.parseModule(file.text).classes.any {
            it.name == name && it.nameStart >= range.startOffset && it.nameEnd <= range.endOffset
        }
    }
}
