#!/usr/bin/env python3
"""Генерация user_fields.json — снимка полей модели пользователя фреймворка.

Снимок играет роль **пола**: имена, доступные в Jinja как `user.<name>` и
объявленные не приложением, а библиотекой. Без него фича не даёт ничего на
типовом приложении — `CustomUser.fields` там возвращает `super().fields + []`,
то есть все имена приходят из `scenarios.user.user_model.User`.

Разбор — через `ast`: вендоренные модули читаются в контролируемом окружении, и
точный парсер здесь дешевле и надёжнее текстового сканера, которым разбирается
произвольный код приложения.

Имена ключей DSL (`fields`, `Field`) и класс по умолчанию читаются из снимка
контракта `shared/rules/rules.json`, а не хранятся здесь копией: два источника
правды для одного факта — ровно та ошибка, ради которой контракт и заведён.
Отсюда порядок запуска: сначала `exportRules`, потом этот скрипт.

Списки гасителей (`blockerTokens`, `blockerConstructs`) генератор **не
использует**: `diagnosticsSafe` — решение, принятое при вендоринге, а не
результат их прогона (см. комментарий у DIAGNOSTICS_SAFE). Гасители нужны
сканеру кода приложения, который читает произвольные модули.

Запуск:
    python tools/generate_user_fields.py [-o out.json] [--check]
"""
import argparse
import ast
import hashlib
import json
import os
import sys
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
VENDOR = os.path.join(HERE, "vendor")
RULES_SNAPSHOT = os.path.join(HERE, "..", "shared", "rules", "rules.json")
DEFAULT_OUT = os.path.join(HERE, "..", "shared", "keywords", "user_fields.json")

# Вендоренные модули: (путь в фреймворке, точечное имя, файл в tools/vendor).
# Путь в фреймворке хранится ради provenance — по нему видно, что именно
# скопировано; точечное имя нужно, чтобы собрать `libraryBase` вида
# `scenarios.user.user_model.User`.
VENDORED_MODULES = [
    ("core/model/model.py", "core.model.model", "model.py"),
    ("core/model/base_user.py", "core.model.base_user", "base_user.py"),
    ("scenarios/user/user_model.py", "scenarios.user.user_model", "user_model.py"),
    # Сигнатура `Field(name, model, ...)` задаёт правило извлечения: имя атрибута
    # — первый позиционный аргумент. Её изменение обязано ронять --check наравне
    # с изменением самих классов модели, поэтому файл тоже вендорится.
    ("core/model/field.py", "core.model.field", "field.py"),
]

# Классы, для которых снимок утверждает безопасность диагностики.
#
# Флаг НЕ вычисляется прогоном гасителей: `core/model/model.py` содержит
# `setattr(self, field.name, obj)` — токен `setattr(` из `blockerTokens`, — но
# стоит он ровно в том цикле, который фича и моделирует. Механическая проверка
# дала бы `false` у всей библиотечной половины, и WARNING не сработал бы ни в
# одном приложении. Гасители — эвристика для кода, который мы не читали;
# вендоренные модули прочитаны, и решение принято человеком.
#
# Пересматривать обязывает sha256 источника: изменился вендоренный файл —
# `--check` краснеет, человек перечитывает диффы и подтверждает флаг.
DIAGNOSTICS_SAFE = {
    "core.model.model.Model": True,
    "core.model.base_user.BaseUser": True,
    "scenarios.user.user_model.User": True,
}

LETTER_CATEGORIES = frozenset({"Lu", "Ll", "Lt", "Lm", "Lo"})


def is_identifier_start(char):
    """Начало идентификатора: `_` или буква (категория `L*`)."""
    return char == "_" or unicodedata.category(char) in LETTER_CATEGORIES


def is_identifier_part(char):
    """Продолжение: начало плюс десятичная цифра (категория `Nd`)."""
    return is_identifier_start(char) or unicodedata.category(char) == "Nd"


def is_addressable_name(name):
    """Адресуемо ли имя как один сегмент Jinja (`user.<name>`).

    Обход — по UTF-16-единицам, как в лексерах обеих реализаций: символ вне BMP
    (`𐐀`) идентификатором не является, и результат не должен зависеть от того,
    что Python хранит его одним code point'ом.

    Категории берутся именно как категории: `str.isalpha()` и `str.isdigit()`
    для этого не годятся — `isdigit()` принимает `²` категории `No`, которую
    `\\p{Nd}` и `Character.isDigit` отвергают.
    """
    if not name:
        return False
    units = name.encode("utf-16-le", errors="surrogatepass")
    if len(units) % 2 != 0:
        return False
    chars = [units[i : i + 2].decode("utf-16-le", errors="surrogatepass") for i in range(0, len(units), 2)]
    if not is_identifier_start(chars[0]):
        return False
    return all(is_identifier_part(c) for c in chars[1:])


def is_offerable_attribute(name):
    """Годится ли имя как атрибут модели: адресуемо и без ведущего `_`."""
    return is_addressable_name(name) and not name.startswith("_")


def load_contract(path=RULES_SNAPSHOT):
    """Читает имена ключей из снимка контракта, падая при его отсутствии."""
    if not os.path.isfile(path):
        raise SystemExit(
            "contract snapshot is missing: %s\n"
            "run './gradlew :idea-plugin:exportRules' first" % path
        )
    with open(path, encoding="utf-8") as handle:
        snapshot = json.load(handle)
    user_model = snapshot.get("userModel")
    if not user_model:
        raise SystemExit(
            "contract snapshot has no 'userModel' section: %s\n"
            "run './gradlew :idea-plugin:exportRules' first" % path
        )
    return user_model


def _call_tail_name(node):
    """Последний сегмент имени вызываемого: `Field(...)` и `field.Field(...)`."""
    if not isinstance(node, ast.Call):
        return None
    if isinstance(node.func, ast.Name):
        return node.func.id
    if isinstance(node.func, ast.Attribute):
        return node.func.attr
    return None


def _first_positional_string(call):
    if not call.args:
        return None
    first = call.args[0]
    return first.value if isinstance(first, ast.Constant) and isinstance(first.value, str) else None


def _is_super_fields(node, fields_property):
    """`super().fields` — обращение к атрибуту, а не вызов."""
    return (
        isinstance(node, ast.Attribute)
        and node.attr == fields_property
        and isinstance(node.value, ast.Call)
        and isinstance(node.value.func, ast.Name)
        and node.value.func.id == "super"
    )


def _field_names_from_list(node, field_factory):
    """Имена из литерала списка `[Field("a", A), ...]`; None — форма не разобрана."""
    if not isinstance(node, ast.List):
        return None
    names = []
    for element in node.elts:
        if _call_tail_name(element) != field_factory:
            return None
        name = _first_positional_string(element)
        if name is None:
            return None
        names.append(name)
    return names


def parse_fields_property(func, fields_property, field_factory):
    """Разбирает свойство `fields`.

    Возвращает `(состояние, имена)`, где состояние — одно из
    `with_super` / `without_super` / `unparsed`.
    """
    body = [stmt for stmt in func.body if not isinstance(stmt, ast.Expr)]
    if len(body) != 1 or not isinstance(body[0], ast.Return) or body[0].value is None:
        return "unparsed", []
    value = body[0].value

    own = _field_names_from_list(value, field_factory)
    if own is not None:
        return "without_super", own

    if isinstance(value, ast.BinOp) and isinstance(value.op, ast.Add):
        left_is_super = _is_super_fields(value.left, fields_property)
        own = _field_names_from_list(value.right, field_factory)
        if left_is_super and own is not None:
            return "with_super", own
    return "unparsed", []


class ClassInfo:
    def __init__(self, name, dotted, dotted_module, bases, imports):
        self.name = name
        self.dotted = dotted
        self.dotted_module = dotted_module
        # Базы как они записаны в заголовке: `ast.Name` даёт имя, любая другая
        # форма (`mod.Base`, распаковка) — None, и это отказ, а не «базы нет».
        self.bases = bases
        # Имя в этом модуле -> точечное имя цели из `from <module> import <name>`
        # (с учётом `as`-алиаса).
        self.imports = imports
        self.fields_state = "absent"
        self.own_fields = []
        self.own_attributes = []


def _self_attribute_targets(node):
    """Имена из `self.x = ...` и `self.x: T = ...` (с присвоенным значением)."""
    targets = []
    if isinstance(node, ast.Assign):
        candidates = node.targets
    elif isinstance(node, ast.AnnAssign):
        # Голая аннотация значения не присваивает и атрибута не создаёт.
        candidates = [node.target] if node.value is not None else []
    else:
        return targets
    for target in candidates:
        if (
            isinstance(target, ast.Attribute)
            and isinstance(target.value, ast.Name)
            and target.value.id == "self"
        ):
            targets.append(target.attr)
    return targets


def _class_level_names(node):
    """Имена уровня класса с присвоенным значением."""
    if isinstance(node, ast.Assign):
        return [t.id for t in node.targets if isinstance(t, ast.Name)]
    if isinstance(node, ast.AnnAssign) and node.value is not None and isinstance(node.target, ast.Name):
        return [node.target.id]
    return []


def collect_class(node, dotted_module, imports, fields_property, field_factory):
    bases = [base.id if isinstance(base, ast.Name) else None for base in node.bases]
    info = ClassInfo(
        node.name,
        "%s.%s" % (dotted_module, node.name),
        dotted_module,
        bases,
        imports,
    )

    for statement in node.body:
        if isinstance(statement, (ast.FunctionDef, ast.AsyncFunctionDef)):
            info.own_attributes.append(statement.name)
            if statement.name == fields_property:
                info.fields_state, info.own_fields = parse_fields_property(
                    statement, fields_property, field_factory
                )
            for inner in ast.walk(statement):
                info.own_attributes.extend(_self_attribute_targets(inner))
            continue
        info.own_attributes.extend(_class_level_names(statement))
    return info


def parse_vendored(fields_property, field_factory):
    """Классы всех вендоренных модулей: точечное имя -> ClassInfo."""
    classes = {}
    for _, dotted_module, filename in VENDORED_MODULES:
        path = os.path.join(VENDOR, filename)
        with open(path, encoding="utf-8") as handle:
            tree = ast.parse(handle.read(), filename=path)
        imports = {}
        for node in tree.body:
            if isinstance(node, ast.ImportFrom) and node.module and node.level == 0:
                for alias in node.names:
                    # Ключ — имя в этом модуле, значение — точечное имя цели.
                    # Хранить один модуль нельзя: у `from m import Base as Parent`
                    # цель называется `m.Base`, а не `m.Parent`.
                    imports[alias.asname or alias.name] = "%s.%s" % (node.module, alias.name)
        for node in tree.body:
            if isinstance(node, ast.ClassDef):
                info = collect_class(node, dotted_module, imports, fields_property, field_factory)
                if info.dotted in classes:
                    raise SystemExit("duplicate class in vendored modules: %s" % info.dotted)
                classes[info.dotted] = info
    return classes


def _resolve_base(info, classes):
    """База класса среди вендоренных: `None` — базы нет, исключение — не разобрали.

    Разрешение идёт **по импортам**, а не по короткому имени. Совпадение имён
    в разных модулях фреймворка ничем не запрещено, и выбор первого попавшегося
    собрал бы неверный снимок молча — без единой красной проверки.
    """
    if not info.bases:
        return None
    if len(info.bases) > 1:
        raise SystemExit(
            "multiple inheritance is not supported in the snapshot: %s" % info.dotted
        )

    base_name = info.bases[0]
    if base_name is None:
        raise SystemExit(
            "base of %s is not a plain name (qualified or unpacked) — cannot resolve it"
            % info.dotted
        )

    # `from <module> import <Base>` — обычная форма во фреймворке; alias-форма
    # `... as <Other>` тоже: карта импортов хранит точечное имя цели.
    imported = info.imports.get(base_name)
    if imported is not None:
        return classes.get(imported)
    # Класс, объявленный рядом в том же модуле.
    return classes.get("%s.%s" % (info.dotted_module, base_name))


def fold(info, classes, seen=None):
    """Свёртка `fields` и объединение `attributes` по цепочке.

    Шаг четырёхзначный — ровно тот, что рантайм применяет к цепочке приложения:
    свойство не объявлено (наследуется как есть), форма не разобрана (поля базы
    сохраняются), объявлено с `super().fields` (объединение), объявлено без него
    (поля базы отбрасываются).
    """
    seen = seen or set()
    if info.dotted in seen:
        raise SystemExit("inheritance cycle in vendored modules: %s" % info.dotted)
    seen = seen | {info.dotted}

    base = _resolve_base(info, classes)
    base_fields, base_attributes = ([], []) if base is None else fold(base, classes, seen)

    if info.fields_state == "absent":
        fields = list(base_fields)
    elif info.fields_state == "unparsed":
        fields = list(base_fields) + list(info.own_fields)
    elif info.fields_state == "with_super":
        fields = list(base_fields) + list(info.own_fields)
    else:
        fields = list(info.own_fields)

    attributes = list(base_attributes) + list(info.own_attributes)
    return fields, attributes


def _unique_offerable(names):
    seen = set()
    result = []
    for name in names:
        if name in seen or not is_offerable_attribute(name):
            continue
        seen.add(name)
        result.append(name)
    return sorted(result)


def build_snapshot(contract):
    classes = parse_vendored(contract["fieldsProperty"], contract["fieldFactory"])
    result = {}
    for dotted, info in classes.items():
        if dotted not in DIAGNOSTICS_SAFE:
            # Класс из вендоренного модуля, о котором решения не принято:
            # молча выкладывать его в пол нельзя, флаг обязателен.
            continue
        _assert_base_resolvable(info, classes)
        fields, attributes = fold(info, classes)
        # Поле и атрибут — разные роли одного имени; в `attributes` попадает всё,
        # что не создаётся `Model.__init__` из списка `fields`.
        field_names = _unique_offerable(fields)
        result[dotted] = {
            "fields": field_names,
            "attributes": [n for n in _unique_offerable(attributes) if n not in set(field_names)],
            "diagnosticsSafe": DIAGNOSTICS_SAFE[dotted],
        }
    missing = sorted(set(DIAGNOSTICS_SAFE) - set(result))
    if missing:
        raise SystemExit("classes declared safe but not found in vendored modules: %s" % missing)

    # Класс по умолчанию — пол типового приложения: `USER` там либо не задан
    # вовсе, либо назначает наследника именно его. Снимок без этого класса
    # молча оставил бы такое приложение без библиотечных имён.
    default_class = contract["defaultClass"]
    if default_class not in result:
        raise SystemExit(
            "user model snapshot has no default class '%s' — the typical app would lose its floor"
            % default_class
        )
    # Наличия мало: класс с пустым `fields` — тот же потерянный пол, только
    # проверку присутствия он проходит. Пустой список законен у промежуточных
    # классов (`Model`), но не у того, на который опирается типовое приложение.
    if not result[default_class]["fields"]:
        raise SystemExit(
            "default class '%s' declares no fields — the typical app would lose its floor"
            % default_class
        )
    return result


def _assert_base_resolvable(info, classes):
    """База класса, попадающего в снимок, обязана быть либо отсутствующей, либо вендоренной.

    Молча оборвать цепочку нельзя: если вендорен `User`, но забыт `BaseUser`,
    снимок соберётся без семи полей, и ни одна проверка не покраснеет.
    """
    if not info.bases:
        return
    if _resolve_base(info, classes) is None:
        raise SystemExit(
            "base '%s' of %s is not among vendored modules — vendor it or drop the class"
            % (info.bases[0], info.dotted)
        )


def _sha256(path):
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("-o", "--out", default=DEFAULT_OUT)
    parser.add_argument(
        "--check",
        action="store_true",
        help="перегенерировать и сравнить с закоммиченным снимком, ничего не записывая",
    )
    args = parser.parse_args(argv)

    contract = load_contract()
    classes = build_snapshot(contract)
    if not classes:
        raise SystemExit("user field snapshot is empty — nothing to write")

    payload = {
        "_meta": {
            "generated": True,
            "note": (
                "GENERATED by 'tools/generate_user_fields.py' from vendored framework modules. "
                "Do not edit by hand."
            ),
            "sources": [
                {"file": framework_path, "sha256": _sha256(os.path.join(VENDOR, filename))}
                for framework_path, _, filename in VENDORED_MODULES
            ],
        },
        "classes": {name: classes[name] for name in sorted(classes)},
    }

    rendered = json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    out = os.path.abspath(args.out)

    if args.check:
        if not os.path.isfile(out):
            print("user field snapshot is missing: %s" % out, file=sys.stderr)
            return 1
        with open(out, encoding="utf-8") as handle:
            current = handle.read()
        if current != rendered:
            print("user field snapshot is out of date: %s" % out, file=sys.stderr)
            print("run 'python tools/generate_user_fields.py' to refresh it", file=sys.stderr)
            return 1
        print("user field snapshot is up to date: %s" % out)
        return 0

    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as handle:
        handle.write(rendered)
    print("user field snapshot written to %s (%d classes)" % (out, len(classes)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
