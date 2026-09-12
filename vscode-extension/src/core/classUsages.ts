import { resourceScan } from "./contract";
import { ownerApplicationRoot, pathSegments } from "./files";
import { isIdentifierPart } from "./identifiers";
import type { Location, SmartAppIndex } from "./index";
import { parseModule } from "./pythonScan";
import { hasExcludedDirBelow } from "./resourceKeywords";

/**
 * Поиск использований класса Python: вхождения в DSL слов, под которыми класс
 * зарегистрирован в ресурсах приложения. Порт `SmartAppKeywordClassUsagesSearcher`
 * и `SmartAppCustomKeywordTargets.ofClassName` плагина IDEA.
 */

/**
 * Вхождения слов класса, объявленного в диапазоне [start, end) файла [uri].
 *
 * Класс узнаётся по объявлению, а не по имени: в диапазон обязано попасть имя
 * класса, объявленного в этом файле. Иначе функция или переменная, названная как
 * зарегистрированный класс, получила бы чужие вхождения. Диапазоном бывает и одно
 * имя, и объявление целиком — с телом и декораторами. Класс, вложенный в другой
 * класс, функцию или `if`, сканеру не виден, и поиск от него ничего не даёт.
 *
 * Сопоставление класса со словом текстовое — по правой части регистрации;
 * импорты для неё не разрешаются, как и в IDEA.
 */
export function classKeywordUsages(
  index: SmartAppIndex,
  uri: string,
  text: string,
  start: number,
  end: number,
): Location[] {
  const path = pathSegments(uri).join("/");
  if (!path.endsWith(resourceScan.fileExtension)) return [];
  const appRoot = ownerApplicationRoot(path, index.applicationRoots());
  if (appRoot === undefined) return [];
  // Класс из вендоренной зависимости приложению не принадлежит, хотя и лежит
  // внутри его каталога: тот же фильтр, что у самих вхождений.
  if (hasExcludedDirBelow(path, appRoot)) return [];

  // Сканер записывает только классы верхнего уровня модуля, поэтому в
  // объявление класса попадает имя ровно одного класса — его собственное.
  const declared = parseModule(text).classes.find(
    (cls) => cls.nameStart >= start && cls.nameEnd <= end,
  )?.name;
  if (declared === undefined) return [];

  // Класс, зарегистрированный под одним словом в нескольких категориях, ищется
  // во всех сразу — так же собирает цель `ofClassName` в IDEA.
  const categoriesByName = new Map<string, Set<string>>();
  for (const keyword of index.customKeywordsOfApplication(appRoot)) {
    if (keyword.className !== declared) continue;
    const categories = categoriesByName.get(keyword.name) ?? new Set<string>();
    categories.add(keyword.category);
    categoriesByName.set(keyword.name, categories);
  }

  const result: Location[] = [];
  for (const [name, categories] of categoriesByName) {
    result.push(...index.findKeywordUsages(name, categories, appRoot));
  }
  return result;
}

/**
 * То же для каретки: диапазон — идентификатор под ней. Запасной путь для случая,
 * когда объявление символа узнать не у кого (нет Python-расширения): работает,
 * только если каретка стоит на имени в самом `class X`.
 */
export function classKeywordUsagesAtCaret(
  index: SmartAppIndex,
  uri: string,
  text: string,
  offset: number,
): Location[] {
  let start = offset;
  while (start > 0 && isIdentifierPart(text[start - 1] as string)) start--;
  let end = offset;
  while (end < text.length && isIdentifierPart(text[end] as string)) end++;
  if (start === end) return [];
  return classKeywordUsages(index, uri, text, start, end);
}
