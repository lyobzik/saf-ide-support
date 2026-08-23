#!/usr/bin/env python3
"""Тесты публикации на фейковом транспорте.

Проверяют алгоритм: сеть, авторизацию и формат ответов настоящего GitLab они не
проверяют — для этого есть ручной `release:smoke`. Здесь важно другое: правила
идемпотентности («решает состояние, а не код», точный набор ссылок, сверка
коммита тега) обязаны быть закреплены прогоном, а не только текстом плана.

Запуск: python3 -m unittest discover -s tools/ci -p test_publish_gitlab.py
"""

import contextlib
import hashlib
import io
import os
import tempfile
import unittest
import unittest.mock

import publish_gitlab as pg

API = "https://gitlab.example/api/v4"
# Проект адресуется путём, а не числом: с числовым идентификатором тесты не
# заметили бы отсутствие кодирования слэша в ссылках релиза.
PROJECT = "group/repo"
NAME = "smartapp-dsl"
VERSION = "1.0.0"
TAG = "v1.0.0"
ZIP = f"{NAME}-{VERSION}.zip"
VSIX = f"{NAME}-{VERSION}.vsix"
BODY_A = b"artifact A"
BODY_B = b"artifact B"


def sha(data):
    return hashlib.sha256(data).hexdigest()


class FakeGitLab:
    """Реестр и релизы в памяти.

    Пагинация повторяет поведение GitLab: страница отдаётся по `per_page`, а
    клиент останавливается, когда получил меньше запрошенного.
    """

    def __init__(self):
        self.packages = {}          # version -> {"id": int, "files": [ {file_name, file_sha256} ]}
        self.releases = {}
        self.tags = {}
        self.puts = 0
        self.posts = 0
        self.next_id = 1
        self.put_failure = None     # (status, text)
        self.put_side_effect = None # что происходит в реестре при отказе PUT
        self.on_post = None         # что происходит между чтением тега и POST
        self.protected = []         # шаблоны защищённых тегов
        self.protected_status = 200 # 403 = токен не видит настройку

    # --- наполнение состояния -------------------------------------------
    def add_file(self, version, file_name, content):
        package = self.packages.setdefault(version, {"id": self.next_id, "files": []})
        if package["id"] == self.next_id:
            self.next_id += 1
        package["files"].append({"file_name": file_name, "file_sha256": sha(content)})

    def add_release(self, tag, links, commit):
        self.releases[tag] = {
            "tag_name": tag,
            "commit": {"id": commit},
            "assets": {"links": [
                {"name": n, "url": u, "link_type": t} for n, u, t in sorted(links)
            ]},
        }

    # --- транспорт -------------------------------------------------------
    def get_json(self, path, params=None):
        params = params or {}
        if path == "/packages":
            items = [
                {"id": package["id"], "name": NAME, "version": version}
                for version, package in self.packages.items()
            ]
            return 200, self._page(items, params)
        if path.startswith("/packages/") and path.endswith("/package_files"):
            package_id = int(path.split("/")[2])
            for package in self.packages.values():
                if package["id"] == package_id:
                    return 200, self._page(package["files"], params)
            return 404, None
        if path.startswith("/releases/"):
            tag = path[len("/releases/"):]
            release = self.releases.get(tag)
            return (200, release) if release else (404, None)
        if path == "/protected_tags":
            if self.protected_status != 200:
                return self.protected_status, None
            return 200, self._page([{"name": name} for name in self.protected], params)
        if path.startswith("/repository/tags/"):
            tag = path[len("/repository/tags/"):]
            commit = self.tags.get(tag)
            return (200, {"commit": {"id": commit}}) if commit else (404, None)
        raise AssertionError(f"unexpected GET {path}")

    def put_bytes(self, path, data):
        self.puts += 1
        if self.put_failure is not None:
            if self.put_side_effect:
                self.put_side_effect()
            return self.put_failure
        _, _, _, version, file_name = path.strip("/").split("/", 4)
        self.add_file(version, file_name, data)
        return 201, ""

    def post_json(self, path, payload):
        self.posts += 1
        assert path == "/releases", path
        if self.on_post:
            # Момент между `tag_commit()` и созданием релиза: именно здесь тег
            # успевают переставить.
            self.on_post()
        tag = payload["tag_name"]
        commit = self.tags.get(tag) or payload.get("ref")
        self.tags.setdefault(tag, commit)
        self.releases[tag] = {
            "tag_name": tag,
            "commit": {"id": commit},
            "assets": {"links": payload["assets"]["links"]},
        }
        return 201, self.releases[tag]

    def _page(self, items, params):
        per_page = int(params.get("per_page", 20))
        page = int(params.get("page", 1))
        start = (page - 1) * per_page
        return items[start:start + per_page]


def links_for(version, names):
    return pg.link_set(API, PROJECT, version, names)


class FileTest(unittest.TestCase):
    def test_absent_file_is_uploaded(self):
        api = FakeGitLab()
        self.assertEqual("uploaded", pg.ensure_file(api, VERSION, ZIP, BODY_A))
        self.assertEqual(1, api.puts)

    def test_present_file_is_not_uploaded(self):
        api = FakeGitLab()
        api.add_file(VERSION, ZIP, BODY_A)
        self.assertEqual("present", pg.ensure_file(api, VERSION, ZIP, BODY_A))
        self.assertEqual(0, api.puts)

    def test_different_checksum_is_refused_without_upload(self):
        api = FakeGitLab()
        api.add_file(VERSION, ZIP, BODY_B)
        with self.assertRaises(pg.PublishError):
            pg.ensure_file(api, VERSION, ZIP, BODY_A)
        self.assertEqual(0, api.puts)

    def test_conflict_409_is_resolved_by_state(self):
        api = FakeGitLab()
        api.put_failure = (409, "conflict")
        api.put_side_effect = lambda: api.add_file(VERSION, ZIP, BODY_A)
        self.assertEqual("present", pg.ensure_file(api, VERSION, ZIP, BODY_A))

    def test_duplicate_400_is_resolved_by_state(self):
        api = FakeGitLab()
        api.put_failure = (400, "Duplicate package is not allowed")
        api.put_side_effect = lambda: api.add_file(VERSION, ZIP, BODY_A)
        self.assertEqual("present", pg.ensure_file(api, VERSION, ZIP, BODY_A))

    def test_other_400_stays_an_error(self):
        api = FakeGitLab()
        api.put_failure = (400, "bad request")
        with self.assertRaises(pg.ApiError):
            pg.ensure_file(api, VERSION, ZIP, BODY_A)

    def test_file_on_the_second_page_is_found(self):
        api = FakeGitLab()
        for index in range(pg.PER_PAGE):
            api.add_file(VERSION, f"filler-{index}.txt", BODY_B)
        api.add_file(VERSION, ZIP, BODY_A)
        self.assertEqual("present", pg.ensure_file(api, VERSION, ZIP, BODY_A))
        self.assertEqual(0, api.puts)

    def test_duplicates_with_equal_checksum_are_accepted(self):
        api = FakeGitLab()
        api.add_file(VERSION, ZIP, BODY_A)
        api.add_file(VERSION, ZIP, BODY_A)
        self.assertEqual("present", pg.ensure_file(api, VERSION, ZIP, BODY_A))
        self.assertEqual(0, api.puts)

    def test_duplicates_with_different_checksum_are_refused(self):
        api = FakeGitLab()
        api.add_file(VERSION, ZIP, BODY_A)
        api.add_file(VERSION, ZIP, BODY_B)
        with self.assertRaises(pg.PublishError):
            pg.ensure_file(api, VERSION, ZIP, BODY_A)
        self.assertEqual(0, api.puts)


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.links = links_for(VERSION, [ZIP, VSIX])

    def test_missing_release_is_created_with_both_links(self):
        api = FakeGitLab()
        api.tags[TAG] = "c0ffee"
        self.assertEqual("created", pg.ensure_release(api, TAG, self.links, "c0ffee"))
        self.assertEqual(self.links, pg.links_of(api.releases[TAG]))

    def test_identical_release_is_left_alone(self):
        api = FakeGitLab()
        api.tags[TAG] = "c0ffee"
        api.add_release(TAG, self.links, "c0ffee")
        self.assertEqual("present", pg.ensure_release(api, TAG, self.links, "c0ffee"))
        self.assertEqual(0, api.posts)

    def test_extra_link_is_an_error(self):
        api = FakeGitLab()
        api.tags[TAG] = "c0ffee"
        extra = self.links | {("stray.zip", "https://elsewhere/stray.zip", "package")}
        api.add_release(TAG, extra, "c0ffee")
        with self.assertRaises(pg.PublishError):
            pg.ensure_release(api, TAG, self.links, "c0ffee")

    def test_missing_link_is_an_error(self):
        api = FakeGitLab()
        api.tags[TAG] = "c0ffee"
        api.add_release(TAG, links_for(VERSION, [ZIP]), "c0ffee")
        with self.assertRaises(pg.PublishError):
            pg.ensure_release(api, TAG, self.links, "c0ffee")

    def test_moved_tag_is_an_error(self):
        api = FakeGitLab()
        api.tags[TAG] = "beef"
        api.add_release(TAG, self.links, "c0ffee")
        with self.assertRaises(pg.PublishError):
            pg.ensure_release(api, TAG, self.links, "beef")


class RaceTest(unittest.TestCase):
    """Окно между чтением коммита тега и созданием релиза."""

    def _publish(self, api, **kwargs):
        with contextlib.redirect_stdout(io.StringIO()):
            return pg.publish(
                api, API, PROJECT, VERSION, TAG, [(ZIP, BODY_A), (VSIX, BODY_B)], **kwargs
            )

    def test_tag_moved_between_read_and_create_is_detected(self):
        # Помешать перестановке тега скрипт не может — это делает protected tag;
        # но создать релиз и промолчать он не имеет права: артефакты собраны из
        # одного коммита, а релиз описывал бы другой.
        api = FakeGitLab()
        api.tags[TAG] = "before"
        api.on_post = lambda: api.tags.__setitem__(TAG, "after")
        with self.assertRaises(pg.PublishError):
            self._publish(api)

    def test_created_release_with_wrong_links_is_detected(self):
        api = FakeGitLab()
        api.tags[TAG] = "before"
        # API «принял» POST, но сохранил не то, что просили.
        original = api.post_json

        def broken(path, payload):
            payload = dict(payload, assets={"links": payload["assets"]["links"][:1]})
            return original(path, payload)

        api.post_json = broken
        with self.assertRaises(pg.PublishError):
            self._publish(api)

    def test_release_without_its_tag_is_an_error(self):
        # Тег удалили, релиз остался: сверить его не с чем, и `ref` не повод
        # считать это нормой.
        api = FakeGitLab()
        api.add_file(VERSION, ZIP, BODY_A)
        api.add_file(VERSION, VSIX, BODY_B)
        api.add_release(TAG, links_for(VERSION, [ZIP, VSIX]), "gone")
        with self.assertRaises(pg.PublishError):
            self._publish(api, ref="deadbeef")


class LinkTest(unittest.TestCase):
    def test_project_path_is_encoded_in_release_links(self):
        (_, url, _) = next(iter(links_for(VERSION, [ZIP])))
        self.assertIn("/projects/group%2Frepo/packages/generic/", url)
        self.assertNotIn("/projects/group/repo/", url)


class TransportTest(unittest.TestCase):
    """Единственное место, где тесты трогают настоящий `HttpTransport`.

    Остальное работает на фейке, поэтому выбор заголовка авторизации иначе не
    проверялся бы вовсе: молчаливый откат на `JOB-TOKEN` означал бы, что токен
    для чтения настроек задан, но не используется.
    """

    class _Response:
        status = 200

        def read(self):
            return b"[]"

        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

    def _headers_for(self, **kwargs):
        seen = {}

        def fake_urlopen(request, timeout=None):
            seen.update(dict(request.header_items()))
            return self._Response()

        transport = pg.HttpTransport(API, PROJECT, "secret", **kwargs)
        with unittest.mock.patch.object(pg.urllib.request, "urlopen", fake_urlopen):
            transport.get_json("/protected_tags")
        return seen

    def test_job_token_is_the_default(self):
        self.assertEqual("secret", self._headers_for().get("Job-token"))

    def test_private_token_is_used_when_asked(self):
        headers = self._headers_for(header="PRIVATE-TOKEN")
        self.assertEqual("secret", headers.get("Private-token"))
        self.assertNotIn("Job-token", headers)


class ProtectedTagTest(unittest.TestCase):
    """Предполётная проверка: репозиторий не публикует релиз на незащищённый тег.

    Помешать перестановке тега скрипт не может — это делает настройка проекта.
    Но и делать вид, что настройка есть, он не должен: разница между «в
    документации написано» и «включено» тут стоит подменённого релиза.
    """

    def _api(self):
        api = FakeGitLab()
        api.tags[TAG] = "tagged"
        api.add_file(VERSION, ZIP, BODY_A)
        api.add_file(VERSION, VSIX, BODY_B)
        api.add_release(TAG, links_for(VERSION, [ZIP, VSIX]), "tagged")
        return api

    def _publish(self, api, protection_transport=None):
        with contextlib.redirect_stdout(io.StringIO()):
            return pg.publish(
                api, API, PROJECT, VERSION, TAG, [(ZIP, BODY_A), (VSIX, BODY_B)],
                pipeline_commit="tagged", require_protected_tag=True,
                protection_transport=protection_transport,
            )

    def test_protected_tag_allows_publishing(self):
        api = self._api()
        api.protected = ["v*"]
        self.assertEqual("present", self._publish(api))

    def test_unprotected_tag_refuses_before_uploading(self):
        api = FakeGitLab()
        api.tags[TAG] = "tagged"
        api.protected = ["release-*"]
        with self.assertRaises(pg.PublishError):
            self._publish(api)
        self.assertEqual(0, api.puts)

    def test_unreadable_setting_is_refused(self):
        # Fail-closed: доказательства нет — публикации нет. Никакой сигнал из
        # окружения сюда не подставляется, потому что переменные вручную
        # запущенного пайплайна перекрывают предопределённые.
        api = self._api()
        api.protected_status = 403
        with self.assertRaises(pg.PublishError):
            self._publish(api)
        self.assertEqual(0, api.puts)

    def test_separate_token_reads_the_setting(self):
        # У релизного job'а два транспорта: job-токен грузит артефакты, токен с
        # read_api читает настройки проекта.
        api = self._api()
        api.protected_status = 403
        reader = FakeGitLab()
        reader.protected = ["v*"]
        self.assertEqual("present", self._publish(api, protection_transport=reader))

    def test_rule_on_the_second_page_is_found(self):
        api = self._api()
        reader = FakeGitLab()
        reader.protected = [f"never-{i}-*" for i in range(pg.PER_PAGE)] + ["v*"]
        self.assertEqual("present", self._publish(api, protection_transport=reader))

    def test_rule_missing_across_all_pages_is_refused(self):
        api = self._api()
        reader = FakeGitLab()
        reader.protected = [f"never-{i}-*" for i in range(pg.PER_PAGE + 1)]
        with self.assertRaises(pg.PublishError):
            self._publish(api, protection_transport=reader)


class MainWiringTest(unittest.TestCase):
    """Связывание окружения с проверками.

    Логику проверяют тесты выше; здесь важно, что `main` действительно передаёт
    в неё требование защищённого тега и читает guard из окружения, а не «как бы
    читает».
    """

    def _api(self, protected_status=403):
        api = FakeGitLab()
        api.tags[TAG] = "tagged"
        api.protected_status = protected_status
        return api

    def _run(self, api, env, tmpdir):
        for name, value in env.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value
        for name, body in ((ZIP, BODY_A), (VSIX, BODY_B)):
            with open(os.path.join(tmpdir, name), "wb") as handle:
                handle.write(body)
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return pg.main([TAG, "--dist", tmpdir], transport=api)

    def setUp(self):
        self._saved = dict(os.environ)
        os.environ.update({
            "CI_JOB_TOKEN": "token",
            "CI_COMMIT_SHA": "tagged",
            # Настоящий job получает эти значения от GitLab; скрипт требует их
            # точного совпадения с зафиксированными в коде.
            "CI_API_V4_URL": pg.API_URL,
            "CI_PROJECT_PATH": pg.PROJECT_PATH,
        })
        os.environ.pop(pg.API_TOKEN, None)

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self._saved)

    def test_settings_token_builds_a_private_token_transport(self):
        # Ветка создания второго транспорта иначе не проверяется ничем: удалить
        # её или прочитать не ту переменную — и все остальные тесты останутся
        # зелёными.
        built = []

        def factory(api_url, project_id, token, header="JOB-TOKEN"):
            reader = FakeGitLab()
            reader.protected = ["v*"]
            built.append({"token": token, "header": header, "fake": reader})
            return reader

        api = self._api()          # job-токен настройки прочитать не может
        os.environ[pg.API_TOKEN] = "reader-token"
        with tempfile.TemporaryDirectory() as tmpdir:
            with unittest.mock.patch.object(pg, "HttpTransport", factory):
                code = self._run(api, {}, tmpdir)

        self.assertEqual(0, code)
        self.assertEqual(1, len(built), "должен строиться ровно один транспорт — для настроек")
        self.assertEqual("PRIVATE-TOKEN", built[0]["header"])
        self.assertEqual("reader-token", built[0]["token"])
        # Загрузка артефактов идёт job-токеном, а не токеном настроек.
        self.assertEqual(2, api.puts)
        self.assertEqual(0, built[0]["fake"].puts)

    def _publishable(self):
        # Всё остальное в порядке: настройки читаются, тег защищён. Значит
        # отказать может только сверка окружения — иначе тест не различал бы
        # свою причину и падал бы «просто так».
        api = self._api(protected_status=200)
        api.protected = ["v*"]
        return api

    def test_baseline_publishes(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            self.assertEqual(0, self._run(self._publishable(), {}, tmpdir))

    def test_missing_api_url_is_refused(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            self.assertEqual(1, self._run(self._publishable(), {"CI_API_V4_URL": None}, tmpdir))

    def test_empty_api_url_is_refused(self):
        # Пустая pipeline variable не должна выключать проверку.
        with tempfile.TemporaryDirectory() as tmpdir:
            self.assertEqual(1, self._run(self._publishable(), {"CI_API_V4_URL": ""}, tmpdir))

    def test_missing_project_path_is_refused(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            self.assertEqual(1, self._run(self._publishable(), {"CI_PROJECT_PATH": None}, tmpdir))

    def test_empty_project_path_is_refused(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            self.assertEqual(1, self._run(self._publishable(), {"CI_PROJECT_PATH": ""}, tmpdir))

    def test_foreign_api_url_is_refused(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            code = self._run(
                self._publishable(), {"CI_API_V4_URL": "https://attacker.example/api/v4"}, tmpdir
            )
        self.assertEqual(1, code)

    def test_foreign_project_path_is_refused(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            code = self._run(self._publishable(), {"CI_PROJECT_PATH": "attacker/mirror"}, tmpdir)
        self.assertEqual(1, code)

    def test_release_without_a_readable_setting_fails(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            self.assertEqual(1, self._run(self._api(), {}, tmpdir))

    def test_release_with_a_readable_setting_succeeds(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            api = self._api(protected_status=200)
            api.protected = ["v*"]
            self.assertEqual(0, self._run(api, {}, tmpdir))


class PipelineCommitTest(unittest.TestCase):
    """Разница между production и смоуком: с чем сверять коммит тега."""

    def _api(self):
        api = FakeGitLab()
        api.tags[TAG] = "tagged"
        api.add_file(VERSION, ZIP, BODY_A)
        api.add_file(VERSION, VSIX, BODY_B)
        api.add_release(TAG, links_for(VERSION, [ZIP, VSIX]), "tagged")
        return api

    def _publish(self, api, pipeline_commit):
        with contextlib.redirect_stdout(io.StringIO()):
            return pg.publish(
                api, API, PROJECT, VERSION, TAG,
                [(ZIP, BODY_A), (VSIX, BODY_B)],
                pipeline_commit=pipeline_commit,
            )

    def test_moved_tag_is_detected_without_a_pipeline_commit(self):
        # Смоук не сверяет коммит пайплайна — но переставленный тег обязан
        # ловиться и там: сверка идёт с коммитом самого тега.
        api = self._api()
        api.tags[TAG] = "moved"
        with self.assertRaises(pg.PublishError):
            self._publish(api, None)

    def test_production_publishes_when_tag_and_pipeline_agree(self):
        self.assertEqual("present", self._publish(self._api(), "tagged"))

    def test_smoke_mode_ignores_the_pipeline_commit(self):
        # Тег `smoke` создаётся один раз, а смоук запускается с ветки, ушедшей
        # вперёд: сверка с коммитом пайплайна роняла бы исправное состояние.
        self.assertEqual("present", self._publish(self._api(), None))

    def test_production_refuses_when_the_tag_moved(self):
        with self.assertRaises(pg.PublishError):
            self._publish(self._api(), "another-commit")


if __name__ == "__main__":
    unittest.main()
