#!/usr/bin/env python3
"""Тесты публикации на GitHub на фейковом транспорте.

Проверяют алгоритм, а не GitHub: настоящие коды ответов и приём ассетов
подтвердит только первый релиз. Здесь важно, что правила идемпотентности,
порядок «сначала проверить, потом писать» и работа с черновиком закреплены
прогоном.

Запуск: python3 -m unittest discover -s tools/ci -p 'test_publish_*.py'
"""

import contextlib
import io
import os
import tempfile
import unittest
import unittest.mock

import publish_github as pg

TAG = "v1.0.0"
ZIP = "smartapp-dsl-1.0.0.zip"
VSIX = "smartapp-dsl-1.0.0.vsix"
BODY_A = b"artifact A"
BODY_B = b"artifact BB"
OTHER = b"artifact AX"          # тот же размер, что BODY_B, другие байты
ARTIFACTS = [(ZIP, BODY_A), (VSIX, BODY_B)]
COMMIT = "b" * 40


class FakeGitHub:
    """Релизы в памяти, включая черновики, состояния ассетов и digest."""

    def __init__(self):
        self.releases = []
        self.tags = {}
        self.uploads_count = 0
        self.deletes_count = 0
        self.next_id = 1
        self.upload_result = None       # (status, body) вместо успешной загрузки
        self.upload_side_effect = None  # что при этом происходит на сервере
        self.create_result = None
        self.immutable_enabled = True   # настройка репозитория «immutable releases»
        self.patch_ignores_draft = False
        self.tag_moves_on_patch = None  # тег уезжает, пока идёт публикация

    # --- наполнение ---
    def add_release(self, tag, assets=(), commit=COMMIT, draft=False, immutable=None):
        release = {
            "id": self.next_id,
            "tag_name": tag,
            "draft": draft,
            "immutable": (not draft and self.immutable_enabled) if immutable is None else immutable,
            "target_commitish": commit,
            "assets": [dict(a) for a in assets],
        }
        self.next_id += 1
        self.releases.append(release)
        return release

    def asset(self, name, content, state=pg.UPLOADED, digest=None):
        return {
            "id": self.next_id + 100,
            "name": name,
            "size": len(content),
            "state": state,
            "digest": digest if digest is not None else pg.digest_of(content),
        }

    def _find(self, tag):
        return next((r for r in self.releases if r["tag_name"] == tag), None)

    # --- транспорт ---
    def get_json(self, path, params=None):
        if path.startswith("/releases/tags/"):
            release = self._find(path[len("/releases/tags/"):])
            return (200, release) if release and not release["draft"] else (404, None)
        if path == "/releases":
            page, per = int((params or {}).get("page", 1)), int((params or {}).get("per_page", 30))
            return 200, self.releases[(page - 1) * per: page * per]
        if path.startswith("/git/ref/tags/"):
            sha = self.tags.get(path[len("/git/ref/tags/"):])
            return (200, {"object": {"type": "commit", "sha": sha}}) if sha else (404, None)
        raise AssertionError(f"unexpected GET {path}")

    def post_json(self, path, payload):
        assert path == "/releases", path
        if self.create_result is not None:
            return self.create_result
        release = self.add_release(payload["tag_name"], commit=self.tags.get(payload["tag_name"]),
                                   draft=payload.get("draft", False))
        return 201, release

    def patch_json(self, path, payload):
        release_id = int(path.split("/")[-1])
        for release in self.releases:
            if release["id"] == release_id:
                if self.tag_moves_on_patch:
                    self.tags[release["tag_name"]] = self.tag_moves_on_patch
                if self.patch_ignores_draft:
                    # API ответил 200, но черновик остался черновиком.
                    return 200, release
                release.update(payload)
                if not release["draft"]:
                    release["immutable"] = self.immutable_enabled
                return 200, release
        return 404, None

    def delete(self, path):
        self.deletes_count += 1
        asset_id = int(path.split("/")[-1])
        for release in self.releases:
            before = len(release["assets"])
            release["assets"] = [a for a in release["assets"] if a["id"] != asset_id]
            if len(release["assets"]) != before:
                return 204, None
        return 404, None

    def upload(self, release_id, name, content):
        self.uploads_count += 1
        if self.upload_result is not None:
            if self.upload_side_effect:
                self.upload_side_effect(release_id, name, content)
            return self.upload_result
        for release in self.releases:
            if release["id"] == release_id:
                release["assets"].append(self.asset(name, content))
                return 201, {}
        return 404, {}


def publish(api, **kwargs):
    with contextlib.redirect_stdout(io.StringIO()):
        return pg.publish(api, TAG, ARTIFACTS, **kwargs)


def names_of(release):
    return {a["name"] for a in release["assets"]}


class PublishTest(unittest.TestCase):
    def _api(self):
        api = FakeGitHub()
        api.tags[TAG] = COMMIT
        return api

    def test_release_is_created_as_a_draft_and_published_last(self):
        api = self._api()
        self.assertEqual("created", publish(api))
        release = api.releases[0]
        self.assertFalse(release["draft"], "черновик обязан быть опубликован в конце")
        self.assertEqual({ZIP, VSIX}, names_of(release))

    def test_failed_upload_leaves_the_release_unpublished(self):
        # Публичный пустой релиз — худший исход обрыва: пользователь видит
        # готовый релиз без файлов.
        api = self._api()
        api.upload_result = (502, {"message": "server error"})
        with self.assertRaises(pg.ApiError):
            publish(api)
        self.assertTrue(api.releases[0]["draft"])

    def test_repeat_uploads_nothing(self):
        api = self._api()
        publish(api)
        before = api.uploads_count
        self.assertEqual("present", publish(api))
        self.assertEqual(before, api.uploads_count)

    def test_missing_asset_is_added_on_a_repeat(self):
        api = self._api()
        api.add_release(TAG, [api.asset(ZIP, BODY_A)], draft=True)
        self.assertEqual("updated", publish(api))
        self.assertEqual({ZIP, VSIX}, names_of(api.releases[0]))
        self.assertEqual(1, api.uploads_count)

    def test_incomplete_asset_is_deleted_and_uploaded_again(self):
        # Обрыв на 502 оставляет ассет в состоянии `starter`: имя занято, файла
        # нет. Считать его готовым нельзя.
        api = self._api()
        api.add_release(TAG, [api.asset(VSIX, b"", state="starter", digest=None)], draft=True)
        self.assertEqual("updated", publish(api))
        self.assertEqual(1, api.deletes_count)
        self.assertEqual({ZIP, VSIX}, names_of(api.releases[0]))
        self.assertTrue(all(a["state"] == pg.UPLOADED for a in api.releases[0]["assets"]))

    def test_same_size_different_bytes_is_refused(self):
        # Упаковка не побайтово воспроизводима: два прогона дают .vsix одного
        # размера с разными байтами. Сверка по размеру приняла бы подмену.
        api = self._api()
        api.add_release(TAG, [api.asset(ZIP, BODY_A), api.asset(VSIX, OTHER)])
        self.assertEqual(len(OTHER), len(BODY_B))
        with self.assertRaises(pg.PublishError):
            publish(api)

    def test_asset_without_a_digest_is_refused(self):
        # Отказать тут можно и сравнением (пустой digest ни с чем не сойдётся),
        # но человек, читающий лог, должен увидеть причину, а не «другой
        # digest: None». Поэтому закрепляется именно формулировка.
        api = self._api()
        api.add_release(TAG, [api.asset(ZIP, BODY_A, digest=""), api.asset(VSIX, BODY_B)])
        with self.assertRaisesRegex(pg.PublishError, "without a digest"):
            publish(api)

    def test_stray_asset_is_refused_before_any_write(self):
        api = self._api()
        api.add_release(TAG, [api.asset(ZIP, BODY_A), api.asset("stray.txt", b"x")], draft=True)
        with self.assertRaises(pg.PublishError):
            publish(api)
        self.assertEqual(0, api.uploads_count, "до записей дело доходить не должно")
        self.assertEqual(0, api.deletes_count)

    def test_upload_conflict_is_resolved_by_state(self):
        # Загрузка дошла, но ответ потерялся: решает состояние, а не код.
        api = self._api()

        def save_anyway(release_id, name, content):
            for release in api.releases:
                if release["id"] == release_id:
                    release["assets"].append(api.asset(name, content))

        api.upload_result = (422, {"message": "already_exists"})
        api.upload_side_effect = save_anyway
        self.assertEqual("created", publish(api))
        self.assertEqual({ZIP, VSIX}, names_of(api.releases[0]))

    def test_draft_is_found_on_a_repeat_instead_of_creating_a_second(self):
        api = self._api()
        api.add_release(TAG, [], draft=True)
        publish(api)
        self.assertEqual(1, len(api.releases), "второй релиз на тот же тег создаваться не должен")

    def test_published_release_is_not_edited_in_place(self):
        # Неполный публичный релиз чинит человек: доливка означала бы окно, в
        # котором пользователь видит релиз с половиной файлов.
        api = self._api()
        api.add_release(TAG, [api.asset(ZIP, BODY_A)], draft=False)
        with self.assertRaises(pg.PublishError):
            publish(api)
        self.assertEqual(0, api.uploads_count)
        self.assertEqual(0, api.deletes_count)

    def test_complete_published_release_is_accepted(self):
        api = self._api()
        api.add_release(TAG, [api.asset(ZIP, BODY_A), api.asset(VSIX, BODY_B)], draft=False)
        self.assertEqual("present", publish(api))

    def test_mutable_release_is_refused(self):
        # Без неизменности сверка digest защищает только до момента публикации.
        api = self._api()
        api.immutable_enabled = False
        with self.assertRaises(pg.PublishError):
            publish(api)

    def test_draft_that_stays_a_draft_after_patch_is_an_error(self):
        # `200` на PATCH не доказывает, что релиз опубликован.
        api = self._api()
        api.patch_ignores_draft = True
        with self.assertRaises(pg.ApiError):
            publish(api)

    def test_tag_moved_while_publishing_is_detected(self):
        api = self._api()
        api.tag_moves_on_patch = "c" * 40
        with self.assertRaises(pg.PublishError):
            publish(api)

    def test_missing_tag_is_refused(self):
        with self.assertRaises(pg.PublishError):
            publish(FakeGitHub())

    def test_moved_tag_is_refused(self):
        api = self._api()
        api.add_release(TAG, [api.asset(ZIP, BODY_A), api.asset(VSIX, BODY_B)], commit="a" * 40)
        with self.assertRaises(pg.PublishError):
            publish(api)

    def test_branch_name_in_target_commitish_is_not_a_mismatch(self):
        api = self._api()
        api.add_release(TAG, [api.asset(ZIP, BODY_A), api.asset(VSIX, BODY_B)], commit="master")
        self.assertEqual("present", publish(api))

    def test_tag_moved_after_the_run_started_is_refused(self):
        api = self._api()
        with self.assertRaises(pg.PublishError):
            publish(api, pipeline_commit="c" * 40)


class MainWiringTest(unittest.TestCase):
    def setUp(self):
        self._saved = dict(os.environ)
        os.environ.update({
            "GITHUB_API_URL": pg.API_URL,
            "GITHUB_REPOSITORY": pg.REPOSITORY,
            "GITHUB_TOKEN": "token",
            "GITHUB_SHA": COMMIT,
        })

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self._saved)

    def _api(self):
        api = FakeGitHub()
        api.tags[TAG] = COMMIT
        return api

    def _run(self, api, env, tmpdir, artifacts=ARTIFACTS):
        for name, value in env.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value
        for name, body in artifacts:
            with open(os.path.join(tmpdir, name), "wb") as handle:
                handle.write(body)
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return pg.main([TAG, "--dist", tmpdir], transport=api)

    def test_baseline_publishes(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            self.assertEqual(0, self._run(self._api(), {}, tmpdir))

    def test_foreign_repository_is_refused(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            self.assertEqual(1, self._run(self._api(), {"GITHUB_REPOSITORY": "attacker/fork"}, tmpdir))

    def test_missing_repository_is_refused(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            self.assertEqual(1, self._run(self._api(), {"GITHUB_REPOSITORY": None}, tmpdir))

    def test_foreign_api_url_is_refused(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            self.assertEqual(1, self._run(self._api(), {"GITHUB_API_URL": "https://attacker.example"}, tmpdir))

    def test_missing_token_is_refused_before_any_request(self):
        # Транспорт здесь намеренно не подменяется: подменённый берётся раньше,
        # чем читается токен, и тест ничего бы не проверял. Сети при этом быть
        # не должно — отказ происходит до первого запроса.
        os.environ.pop("GITHUB_TOKEN", None)

        def no_network(*_args, **_kwargs):
            raise AssertionError("без токена среды сети быть не должно")

        errors = io.StringIO()
        with tempfile.TemporaryDirectory() as tmpdir:
            for name, body in ARTIFACTS:
                with open(os.path.join(tmpdir, name), "wb") as handle:
                    handle.write(body)
            with unittest.mock.patch.object(pg.urllib.request, "urlopen", no_network):
                with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(errors):
                    code = pg.main([TAG, "--dist", tmpdir])
        self.assertEqual(1, code)
        # Причина отказа проверяется явно: иначе тест зелёный и когда скрипт
        # взял автоматический GITHUB_TOKEN и упал уже на ответе API.
        self.assertIn("GITHUB_TOKEN", errors.getvalue())

    def test_missing_artifact_is_refused(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            self.assertEqual(1, self._run(self._api(), {}, tmpdir, artifacts=[(ZIP, BODY_A)]))


if __name__ == "__main__":
    unittest.main()
