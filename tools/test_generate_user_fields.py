#!/usr/bin/env python3
"""Тесты генератора снимка модели пользователя.

Зачем отдельно от `--check`: тот сверяет снимок с тем, что генератор породил
**сейчас**, то есть неверный генератор спокойно породит и примет согласованный
неверный снимок. Здесь проверяется поведение самого генератора — и на общей
таблице имён, и на синтетическом источнике, через **готовый снимок**, а не
через импортированный предикат: импорт предиката зелен и тогда, когда генератор
его не вызывает.
"""
import ast
import json
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import generate_user_fields as gen  # noqa: E402

TABLE = os.path.join(HERE, "..", "shared", "predicates", "attribute-names.json")


def _escape(name):
    """Имя как литерал внутри Python-исходника, только ASCII.

    Всё за пределами печатной ASCII уходит в escape: одиночный суррогат
    литерально в UTF-8-файл не положить, а символ вне BMP требует `\\U` с
    восемью цифрами — `\\u` с четырьмя разобрал бы его как два других символа.
    """
    out = []
    for char in name:
        code = ord(char)
        if 0x20 <= code < 0x7F and char not in '"\\':
            out.append(char)
        elif code > 0xFFFF:
            out.append("\\U%08x" % code)
        else:
            out.append("\\u%04x" % code)
    return "".join(out)


def load_table():
    with open(TABLE, encoding="utf-8") as handle:
        return json.load(handle)["names"]


class PredicateTableTest(unittest.TestCase):
    """Общая таблица имён — та же, что читают тесты плагина и ядра расширения."""

    def setUp(self):
        self.cases = load_table()

    def test_table_covers_both_sides_of_predicate(self):
        self.assertGreater(len(self.cases), 10)
        self.assertTrue(any(case["addressable"] for case in self.cases))
        self.assertTrue(any(not case["addressable"] for case in self.cases))
        # Ведущее подчёркивание — единственный вход, где addressable и field
        # расходятся; без него колонка field не проверяла бы ничего.
        self.assertTrue(any(case["addressable"] and not case["field"] for case in self.cases))

    def test_addressability_matches_shared_table(self):
        for case in self.cases:
            with self.subTest(name=case["input"], why=case["why"]):
                self.assertEqual(case["addressable"], gen.is_addressable_name(case["input"]))

    def test_field_eligibility_matches_shared_table(self):
        for case in self.cases:
            with self.subTest(name=case["input"]):
                self.assertEqual(case["field"], gen.is_offerable_attribute(case["input"]))


class SyntheticSourceTest(unittest.TestCase):
    """Прогон генератора на синтетическом источнике — через готовый снимок."""

    def build(self, source, dotted="probe.module", class_names=None):
        """Строит снимок по одному синтетическому модулю."""
        with tempfile.TemporaryDirectory() as vendor:
            path = os.path.join(vendor, "probe.py")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(source)

            saved = (gen.VENDOR, gen.VENDORED_MODULES, gen.DIAGNOSTICS_SAFE)
            gen.VENDOR = vendor
            gen.VENDORED_MODULES = [("probe/module.py", dotted, "probe.py")]
            gen.DIAGNOSTICS_SAFE = {
                "%s.%s" % (dotted, name): True for name in (class_names or ["Probe"])
            }
            try:
                # Контракт берётся настоящий: тест заодно проверяет, что
                # генератор читает имена ключей оттуда, а не хранит копию.
                # Подменяется только класс по умолчанию — снимок обязан его
                # содержать, а у синтетического источника он свой.
                contract = dict(gen.load_contract())
                contract["defaultClass"] = "%s.%s" % (dotted, (class_names or ["Probe"])[-1])
                return gen.build_snapshot(contract)
            finally:
                gen.VENDOR, gen.VENDORED_MODULES, gen.DIAGNOSTICS_SAFE = saved

    def test_every_table_row_is_filtered_as_declared(self):
        """Каждая строка общей таблицы — своим `Field(...)` в источнике.

        Подмножества здесь мало: неполный источник оставил бы тест зелёным
        ровно там, где генератор ошибся — на цифре в начале, на `$` или на
        арабской десятичной.
        """
        rows = load_table()
        literals = ['        Field("%s", M),' % _escape(case["input"]) for case in rows]
        source = (
            "class Probe:\n"
            "    @property\n"
            "    def fields(self):\n"
            "        return [\n" + "\n".join(literals) + "\n        ]\n"
        )

        # Источник — тоже фикстура, и её легко испортить незаметно: `\\u%04x`
        # для `𐐀` даёт `\u10400`, а это `\u1040` плюс `0`, то есть два других
        # символа. Тест остался бы зелёным, проверив не то. Сверяем, что
        # генератор увидит ровно те имена, что в таблице.
        seen = [
            call.args[0].value
            for call in ast.walk(ast.parse(source))
            if isinstance(call, ast.Call) and call.args
        ]
        self.assertEqual([case["input"] for case in rows], seen)

        snapshot = self.build(source)
        got = set(snapshot["probe.module.Probe"]["fields"])
        expected = {case["input"] for case in rows if case["field"]}
        self.assertEqual(expected, got)

    def test_attribute_forms(self):
        source = (
            "class Probe:\n"
            # Поле нужно только чтобы проба годилась в классы по умолчанию:
            # снимок без полей у него — потерянный пол.
            "    @property\n"
            "    def fields(self):\n"
            "        return [Field('anchor', M)]\n"
            "\n"
            "    limit: int = 5\n"
            "    annotated: int\n"
            "    CONST = 'x'\n"
            "\n"
            "    def __init__(self):\n"
            "        self.visible = 1\n"
            "        self.typed: int = 2\n"
            "        self.bare: int\n"
            "        self._hidden = 3\n"
            "\n"
            "    def method(self):\n"
            "        return None\n"
            "\n"
            "    def _private(self):\n"
            "        return None\n"
        )
        attributes = set(self.build(source)["probe.module.Probe"]["attributes"])
        attributes.discard("fields")
        # Голая аннотация атрибута не создаёт: `class A: x: int` даёт
        # `hasattr(A, "x") == False`. Именно из-за неё имена полей, продублированные
        # аннотациями в BaseUser и User, не возвращались бы в словарь после того,
        # как свёртка их отбросила.
        self.assertEqual({"limit", "CONST", "visible", "typed", "method"}, attributes)

    def test_fields_property_states(self):
        base = (
            "class Base:\n"
            "    @property\n"
            "    def fields(self):\n"
            "        return [Field('base_field', M)]\n"
            "\n"
        )
        # 1. свойство не объявлено — наследуется как есть
        absent = self.build(
            base + "class Probe(Base):\n    pass\n",
            class_names=["Base", "Probe"],
        )
        self.assertEqual(["base_field"], absent["probe.module.Probe"]["fields"])

        # 2. объявлено с super() — объединение
        with_super = self.build(
            base + "class Probe(Base):\n"
            "    @property\n"
            "    def fields(self):\n"
            "        return super().fields + [Field('own', M)]\n",
            class_names=["Base", "Probe"],
        )
        self.assertEqual(["base_field", "own"], with_super["probe.module.Probe"]["fields"])

        # 3. объявлено без super() — поля базы отброшены
        without_super = self.build(
            base + "class Probe(Base):\n"
            "    @property\n"
            "    def fields(self):\n"
            "        return [Field('own', M)]\n",
            class_names=["Base", "Probe"],
        )
        self.assertEqual(["own"], without_super["probe.module.Probe"]["fields"])

        # 4. форму разобрать не удалось — поля базы СОХРАНЯЮТСЯ, распознанные
        # литералы извлекаются. Потерять пол на незнакомой форме записи хуже,
        # чем предложить лишнее.
        unparsed = self.build(
            base + "class Probe(Base):\n"
            "    @property\n"
            "    def fields(self):\n"
            "        return [*super().fields, Field('own', M)]\n",
            class_names=["Base", "Probe"],
        )
        self.assertEqual(["base_field"], unparsed["probe.module.Probe"]["fields"])

    def test_name_present_in_fields_is_not_repeated_in_attributes(self):
        source = (
            "class Probe:\n"
            "    @property\n"
            "    def fields(self):\n"
            "        return [Field('dual', M)]\n"
            "\n"
            "    def __init__(self):\n"
            "        self.dual = 1\n"
        )
        snapshot = self.build(source)["probe.module.Probe"]
        # Иначе отбрасывание полей базы ничего бы не давало: имя вернулось бы
        # через плоское объединение attributes.
        self.assertEqual(["dual"], snapshot["fields"])
        self.assertNotIn("dual", snapshot["attributes"])

    def test_base_is_resolved_by_import_not_by_short_name(self):
        """Одноимённые классы в разных модулях не должны путаться."""
        with tempfile.TemporaryDirectory() as vendor:
            with open(os.path.join(vendor, "left.py"), "w", encoding="utf-8") as handle:
                handle.write(
                    "class Base:\n"
                    "    @property\n"
                    "    def fields(self):\n"
                    "        return [Field('from_left', M)]\n"
                )
            with open(os.path.join(vendor, "right.py"), "w", encoding="utf-8") as handle:
                handle.write(
                    "class Base:\n"
                    "    @property\n"
                    "    def fields(self):\n"
                    "        return [Field('from_right', M)]\n"
                )
            with open(os.path.join(vendor, "probe.py"), "w", encoding="utf-8") as handle:
                handle.write("from pkg.right import Base\n\n\nclass Probe(Base):\n    pass\n")

            saved = (gen.VENDOR, gen.VENDORED_MODULES, gen.DIAGNOSTICS_SAFE)
            gen.VENDOR = vendor
            gen.VENDORED_MODULES = [
                ("pkg/left.py", "pkg.left", "left.py"),
                ("pkg/right.py", "pkg.right", "right.py"),
                ("pkg/probe.py", "pkg.probe", "probe.py"),
            ]
            gen.DIAGNOSTICS_SAFE = {"pkg.probe.Probe": True}
            try:
                contract = dict(gen.load_contract())
                contract["defaultClass"] = "pkg.probe.Probe"
                snapshot = gen.build_snapshot(contract)
            finally:
                gen.VENDOR, gen.VENDORED_MODULES, gen.DIAGNOSTICS_SAFE = saved

        # Резолв по короткому имени взял бы первый попавшийся класс и собрал бы
        # неверный снимок молча — без единой красной проверки.
        self.assertEqual(["from_right"], snapshot["pkg.probe.Probe"]["fields"])

    def test_base_import_alias_is_resolved(self):
        """`from m import Base as Parent` — цель называется `m.Base`, не `m.Parent`."""
        with tempfile.TemporaryDirectory() as vendor:
            with open(os.path.join(vendor, "base.py"), "w", encoding="utf-8") as handle:
                handle.write(
                    "class Base:\n"
                    "    @property\n"
                    "    def fields(self):\n"
                    "        return [Field('base_field', M)]\n"
                )
            with open(os.path.join(vendor, "probe.py"), "w", encoding="utf-8") as handle:
                handle.write(
                    "from pkg.base import Base as Parent\n\n\nclass Probe(Parent):\n    pass\n"
                )

            saved = (gen.VENDOR, gen.VENDORED_MODULES, gen.DIAGNOSTICS_SAFE)
            gen.VENDOR = vendor
            gen.VENDORED_MODULES = [
                ("pkg/base.py", "pkg.base", "base.py"),
                ("pkg/probe.py", "pkg.probe", "probe.py"),
            ]
            gen.DIAGNOSTICS_SAFE = {"pkg.probe.Probe": True}
            try:
                contract = dict(gen.load_contract())
                contract["defaultClass"] = "pkg.probe.Probe"
                snapshot = gen.build_snapshot(contract)
            finally:
                gen.VENDOR, gen.VENDORED_MODULES, gen.DIAGNOSTICS_SAFE = saved

        self.assertEqual(["base_field"], snapshot["pkg.probe.Probe"]["fields"])

    def test_default_class_without_fields_is_an_error(self):
        """Наличия класса мало: пустой `fields` — тот же потерянный пол.

        Проверку присутствия такой снимок проходит, а типовое приложение
        остаётся без библиотечных имён.
        """
        source = (
            "class WithFields:\n"
            "    @property\n"
            "    def fields(self):\n"
            "        return [Field('something', M)]\n"
            "\n\n"
            "class Probe:\n    pass\n"
        )
        with self.assertRaises(SystemExit):
            # Второй класс с полями есть — проверка «хоть у кого-то поля есть»
            # была бы зелёной.
            self.build(source, class_names=["WithFields", "Probe"])

    def test_qualified_base_is_rejected(self):
        with self.assertRaises(SystemExit):
            self.build("import mod\n\n\nclass Probe(mod.Base):\n    pass\n")

    def test_multiple_bases_are_rejected(self):
        source = (
            "class A:\n    pass\n\n\nclass B:\n    pass\n\n\nclass Probe(A, B):\n    pass\n"
        )
        with self.assertRaises(SystemExit):
            self.build(source, class_names=["A", "B", "Probe"])

    def test_base_outside_vendored_modules_is_rejected(self):
        # Иначе вендоренный `User` без вендоренного `BaseUser` дал бы снимок
        # без семи полей, и ни одна проверка не покраснела бы.
        with self.assertRaises(SystemExit):
            self.build("from far.away import Base\n\n\nclass Probe(Base):\n    pass\n")

    def test_class_without_declared_flag_is_not_emitted(self):
        source = (
            "class Probe:\n"
            "    @property\n"
            "    def fields(self):\n"
            "        return [Field('anchor', M)]\n"
            "\n\n"
            "class Other:\n    pass\n"
        )
        snapshot = self.build(source)
        self.assertIn("probe.module.Probe", snapshot)
        self.assertNotIn("probe.module.Other", snapshot)

    def test_snapshot_without_default_class_is_an_error(self):
        """Пол типового приложения обязан быть в снимке.

        `USER` там либо не задан вовсе, либо назначает наследника класса по
        умолчанию. Снимок без него прошёл бы проверку непустоты и молча оставил
        такое приложение без библиотечных имён.
        """
        with tempfile.TemporaryDirectory() as vendor:
            with open(os.path.join(vendor, "probe.py"), "w", encoding="utf-8") as handle:
                handle.write("class Probe:\n    pass\n")
            saved = (gen.VENDOR, gen.VENDORED_MODULES, gen.DIAGNOSTICS_SAFE)
            gen.VENDOR = vendor
            gen.VENDORED_MODULES = [("probe/module.py", "probe.module", "probe.py")]
            gen.DIAGNOSTICS_SAFE = {"probe.module.Probe": True}
            try:
                # Контракт настоящий: его defaultClass в синтетическом снимке
                # отсутствует, и это обязано быть отказом.
                with self.assertRaises(SystemExit):
                    gen.build_snapshot(gen.load_contract())
            finally:
                gen.VENDOR, gen.VENDORED_MODULES, gen.DIAGNOSTICS_SAFE = saved

    def test_declared_class_missing_from_sources_is_an_error(self):
        with self.assertRaises(SystemExit):
            self.build("class Probe:\n    pass\n", class_names=["Probe", "Ghost"])


if __name__ == "__main__":
    unittest.main()
