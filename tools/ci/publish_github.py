#!/usr/bin/env python3
"""Публикация релиза на GitHub: Release с двумя ассетами.

Второй провайдер-специфичный слой CI рядом с `publish_gitlab.py`. Общее у них
не код, а дисциплина: публикация обязана быть идемпотентной — повторный запуск
после обрыва доводит её до конца либо внятно отказывает, но второго состояния
не создаёт. Поэтому здесь тоже «прочитать → сравнить → решить», а не
`gh release create` в надежде, что второй раз никто не нажмёт.

Порядок операций выбран так, чтобы наполовину сделанная публикация не была
видна пользователю и не считалась готовой:

1. релиз создаётся **черновиком** — упавшая загрузка не оставляет публичный
   пустой релиз;
2. состояние проверяется **до единой записи**: чужой ассет — отказ сразу, а не
   после того, как мы дольём свои;
3. ассеты сверяются по **`digest`** (`sha256:…`), который отдаёт API. Размер
   для этого не годится: два прогона упаковки дают `.vsix` одного размера с
   разными байтами;
4. недогруженный ассет (`state != "uploaded"` — так выглядит обрыв на 502)
   удаляется и грузится заново, а не считается готовым;
5. черновик публикуется последним — только когда оба ассета на месте и сошлись
   по digest, а результат публикации перечитывается: `200` на `PATCH` не
   доказывает, что релиз перестал быть черновиком;
6. уже **опубликованный** релиз не правится на месте. Неполный публичный релиз
   — отказ: доливка означала бы окно, в котором пользователь видит релиз с
   половиной файлов, и противоречила бы неизменности релизов.

Неизменность (immutable releases) — предусловие, а не побочный эффект: без неё
опубликованный релиз можно подменить, и вся сверка digest защищает только до
момента публикации. Настраивается она в репозитории; скрипт проверяет
результат и отказывается считать публикацию успешной, если релиз изменяем.

**Модель угроз здесь узкая, и это записано намеренно.** Доверенными считаются
все, у кого есть право записи в репозиторий: роль Write позволяет создавать и
редактировать Releases напрямую, без всякого workflow, — значит отделить
публикацию от такого участника внутри одного репозитория технически нельзя.
Ни среда с подтверждением, ни отдельный токен этого не меняют: обойти их можно,
даже не трогая CI.

Отсюда честная граница ответственности. Скрипт защищает от **случайностей**:
обрыва между загрузками, гонок, наполовину опубликованного релиза, несовпадения
артефактов с тем, что уже выложено, повторного запуска, переставленного тега.
Плюс от подмены **после** выпуска — этим занимаются immutable releases. Он не
защищает от злонамеренного или скомпрометированного участника с правом записи и
не притворяется, что защищает.

Пересматривать это придётся раньше, чем кажется: как только право записи
получает человек или бот, которому нельзя доверить публикацию, Releases нужно
уносить в отдельный репозиторий со своим списком писателей.
"""

import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

# Адреса и репозиторий зафиксированы в коде по той же причине, что и у GitLab:
# публикация не должна зависеть от значения, которое кто-то может подставить.
API_URL = "https://api.github.com"
UPLOAD_URL = "https://uploads.github.com"
REPOSITORY = "lyobzik/saf-ide-support"

PACKAGE_NAME = "smartapp-dsl"
HTTP_TIMEOUT = 60
PER_PAGE = 100
UPLOADED = "uploaded"


class PublishError(Exception):
    """Осмысленный отказ: состояние релиза не то, которое мы готовы принять."""


class ApiError(PublishError):
    """Неожиданный ответ API или сбой транспорта."""


class HttpTransport:
    """Клиент GitHub API на стандартной библиотеке — образу не нужен ни gh, ни jq."""

    def __init__(self, token, api_url=API_URL, upload_url=UPLOAD_URL, repository=REPOSITORY):
        self.token = token
        self.api = f"{api_url.rstrip('/')}/repos/{repository}"
        self.uploads = f"{upload_url.rstrip('/')}/repos/{repository}"
        self.uploads_count = 0
        self.deletes_count = 0

    def _request(self, method, url, data=None, content_type=None, params=None):
        if params:
            url += "?" + urllib.parse.urlencode(params)
        request = urllib.request.Request(url, data=data, method=method)
        request.add_header("Authorization", f"Bearer {self.token}")
        request.add_header("Accept", "application/vnd.github+json")
        request.add_header("X-GitHub-Api-Version", "2022-11-28")
        if content_type:
            request.add_header("Content-Type", content_type)
        try:
            with urllib.request.urlopen(request, timeout=HTTP_TIMEOUT) as response:
                return response.status, _decode(response.read())
        except urllib.error.HTTPError as error:
            return error.code, _decode(error.read())
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise ApiError(f"{method} {url} failed: {error}") from error

    def get_json(self, path, params=None):
        return self._request("GET", self.api + path, params=params)

    def post_json(self, path, payload):
        return self._request("POST", self.api + path, data=json.dumps(payload).encode(),
                             content_type="application/json")

    def patch_json(self, path, payload):
        return self._request("PATCH", self.api + path, data=json.dumps(payload).encode(),
                             content_type="application/json")

    def delete(self, path):
        self.deletes_count += 1
        return self._request("DELETE", self.api + path)

    def upload(self, release_id, name, content):
        self.uploads_count += 1
        url = f"{self.uploads}/releases/{release_id}/assets"
        return self._request("POST", url, data=content,
                             content_type="application/octet-stream",
                             params={"name": name})


def _decode(body):
    if not body:
        return None
    try:
        return json.loads(body)
    except ValueError:
        return None


def digest_of(content):
    return "sha256:" + hashlib.sha256(content).hexdigest()


def _looks_like_sha(value):
    return isinstance(value, str) and len(value) == 40 and all(c in "0123456789abcdef" for c in value)


def tag_commit(transport, tag):
    status, data = transport.get_json(f"/git/ref/tags/{urllib.parse.quote(tag)}")
    if status == 404:
        return None
    if status != 200:
        raise ApiError(f"cannot read tag {tag}: HTTP {status}")
    obj = (data or {}).get("object") or {}
    if obj.get("type") == "tag":  # аннотированный тег: разыменовываем
        status, data = transport.get_json(f"/git/tags/{obj['sha']}")
        if status != 200:
            raise ApiError(f"cannot dereference tag {tag}: HTTP {status}")
        return ((data or {}).get("object") or {}).get("sha")
    return obj.get("sha")


def find_release(transport, tag):
    """Ищет релиз по тегу, включая черновики.

    `GET /releases/tags/:tag` черновики не находит — их видно только перебором.
    Без перебора повторный запуск после обрыва создавал бы второй черновик
    вместо того, чтобы дочитать первый.
    """
    status, release = transport.get_json(f"/releases/tags/{urllib.parse.quote(tag)}")
    if status == 200:
        return release
    if status != 404:
        raise ApiError(f"cannot read release {tag}: HTTP {status}")

    page = 1
    while True:
        status, data = transport.get_json("/releases", {"page": page, "per_page": PER_PAGE})
        if status != 200 or not isinstance(data, list):
            raise ApiError(f"cannot list releases: HTTP {status}")
        for release in data:
            if release.get("tag_name") == tag:
                return release
        if len(data) < PER_PAGE:
            return None
        page += 1


def check_immutable(release, tag):
    """Неизменность опубликованного релиза.

    Без неё ассеты и тег можно подменить после выпуска, и всё, что даёт сверка
    digest, — гарантия «на момент публикации». Проверить настройку репозитория
    заранее нечем: для этого нужен токен с правами администратора, а держать
    такой в CI дороже, чем стоит проверка. Поэтому проверяется результат, и
    несоответствие — отказ: зелёного релиза без неизменности не бывает.
    """
    if (release or {}).get("immutable") is not True:
        raise PublishError(
            f"release {tag} is not immutable. Immutable releases must be enabled in the "
            "repository settings before releasing: without them a published release can be "
            "replaced, and verifying digests at publish time proves nothing afterwards."
        )


def check_commit(release, expected_commit):
    """Сверяет происхождение релиза — насколько это позволяет API.

    В объекте релиза нет коммита, из которого он вырезан: `target_commitish`
    чаще содержит имя ветки (`master`), и сравнивать его с sha значило бы
    отказывать на каждом релизе. Поэтому сверка только когда там действительно
    sha. Основную защиту даёт другая проверка: коммит тега обязан совпасть с
    коммитом, на котором идёт workflow (см. `publish`).
    """
    commit = (release or {}).get("target_commitish")
    if expected_commit is not None and _looks_like_sha(commit) and commit != expected_commit:
        raise PublishError(
            f"release points at {commit}, but the tag points at {expected_commit}: the tag was moved"
        )


def assets_by_name(release):
    return {a.get("name"): a for a in (release or {}).get("assets") or []}


def check_asset(asset, name, content):
    """Ассет соответствует тому, что мы публикуем? `ok` | `redo` | бросает."""
    if asset.get("state") != UPLOADED:
        # Обрыв загрузки оставляет ассет в состоянии `starter`: он занимает имя,
        # но файла за ним нет. Считать его готовым нельзя.
        return "redo"
    actual = asset.get("digest")
    if not actual:
        raise PublishError(
            f"{name} is attached without a digest, so it cannot be verified; "
            "refusing to treat it as published"
        )
    if actual != digest_of(content):
        raise PublishError(
            f"{name} is already attached with a different digest ({actual}): the artifact was "
            "rebuilt or the tag was moved. Packaging is not byte-reproducible, so a rebuilt "
            "artifact never matches a published one."
        )
    return "ok"


def plan_assets(release, artifacts):
    """Что сделать с ассетами. Ничего не пишет — только читает и решает.

    Проверка целиком выполняется до первой записи: иначе чужой ассет
    обнаруживается уже после того, как мы дольём свои в релиз, который всё
    равно забракуем.
    """
    existing = assets_by_name(release)
    expected = {name for name, _ in artifacts}
    stray = sorted(set(existing) - expected)
    if stray:
        raise PublishError(
            f"release has assets this build does not publish: {stray}. "
            "Someone attached them by hand; refusing to touch the release."
        )

    upload, replace = [], []
    for name, content in artifacts:
        asset = existing.get(name)
        if asset is None:
            upload.append(name)
        elif check_asset(asset, name, content) == "redo":
            replace.append((name, asset.get("id")))
    return upload, replace


def upload_asset(transport, release, release_id, tag, name, content):
    status, body = transport.upload(release_id, name, content)
    if status in (200, 201):
        return
    # Отказ разбирается по состоянию, а не по коду: загрузка могла дойти и
    # получить 422 при повторе, а могла не дойти вовсе.
    fresh = find_release(transport, tag)
    asset = assets_by_name(fresh).get(name)
    if asset is not None and check_asset(asset, name, content) == "ok":
        return
    raise ApiError(f"upload of {name} failed: HTTP {status}: {body}")


def ensure_release(transport, tag, artifacts, expected_commit):
    """`created` | `updated` | `present`; иначе бросает."""
    release = find_release(transport, tag)
    outcome = "present"

    if release is None:
        status, release = transport.post_json(
            "/releases",
            {"tag_name": tag, "name": tag, "body": f"Release {tag}",
             "draft": True, "prerelease": False},
        )
        if status not in (200, 201):
            # Гонка: черновик мог создать параллельный запуск.
            release = find_release(transport, tag)
            if release is None:
                raise ApiError(f"cannot create release {tag}: HTTP {status}")
        outcome = "created"

    check_commit(release, expected_commit)
    release_id = (release or {}).get("id")
    if release_id is None:
        raise ApiError(f"release {tag} has no id in the API response")

    if not release.get("draft"):
        # Опубликованный релиз не дописывается: неполный публичный релиз чинится
        # человеком, а не молча дополняется на глазах у тех, кто его уже видит.
        verify(release, artifacts, expected_commit, tag)
        check_immutable(release, tag)
        return "present"

    upload, replace = plan_assets(release, artifacts)
    contents = dict(artifacts)

    for name, asset_id in replace:
        status, _ = transport.delete(f"/releases/assets/{asset_id}")
        if status not in (200, 204, 404):
            raise ApiError(f"cannot delete the incomplete asset {name}: HTTP {status}")

    for name in upload + [name for name, _ in replace]:
        upload_asset(transport, release, release_id, tag, name, contents[name])
    if (upload or replace) and outcome == "present":
        outcome = "updated"

    release = find_release(transport, tag)
    verify(release, artifacts, expected_commit, tag)

    status, _ = transport.patch_json(f"/releases/{release_id}", {"draft": False})
    if status not in (200, 201):
        raise ApiError(f"cannot publish release {tag}: HTTP {status}")

    # Ответ `PATCH` ничего не доказывает: состояние читается заново.
    release = find_release(transport, tag)
    if release is None or release.get("draft"):
        raise ApiError(f"release {tag} is still a draft after publishing")
    verify(release, artifacts, expected_commit, tag)
    check_immutable(release, tag)
    return outcome


def verify(release, artifacts, expected_commit, tag):
    """Итоговая сверка: набор имён, состояние и digest каждого ассета."""
    if release is None:
        raise ApiError(f"release {tag} disappeared while publishing")
    existing = assets_by_name(release)
    expected = {name for name, _ in artifacts}
    if set(existing) != expected:
        raise PublishError(
            "release assets differ from what this build publishes: "
            f"missing={sorted(expected - set(existing))} "
            f"unexpected={sorted(set(existing) - expected)}"
        )
    for name, content in artifacts:
        if check_asset(existing[name], name, content) != "ok":
            raise PublishError(f"{name} is still not uploaded completely")
    check_commit(release, expected_commit)


def publish(transport, tag, artifacts, pipeline_commit=None):
    commit = tag_commit(transport, tag)
    if commit is None:
        raise PublishError(f"tag {tag} does not exist")
    if pipeline_commit and commit != pipeline_commit:
        raise PublishError(
            f"tag {tag} points at {commit}, but the workflow runs on {pipeline_commit}: "
            "the tag was moved after this run started"
        )
    result = ensure_release(transport, tag, artifacts, commit)

    # Тег перечитывается после публикации: он мог сдвинуться, пока мы грузили
    # ассеты, и тогда неизменным стал релиз, описывающий не тот код.
    final = tag_commit(transport, tag)
    if final != commit:
        raise PublishError(
            f"tag {tag} moved from {commit} to {final} while publishing: the release now "
            "describes different code. Restrict tag updates with a ruleset."
        )
    print(f"    release {tag}: {result}")
    return result


def _env(name):
    value = os.environ.get(name)
    if not value:
        raise PublishError(f"{name} is not set: this script runs inside GitHub Actions")
    return value


def _check_environment_matches():
    env_api = os.environ.get("GITHUB_API_URL", "")
    if env_api.rstrip("/") != API_URL:
        raise PublishError(
            f"GITHUB_API_URL is {env_api!r}, but this script only publishes to {API_URL}"
        )
    env_repo = os.environ.get("GITHUB_REPOSITORY", "")
    if env_repo != REPOSITORY:
        raise PublishError(
            f"GITHUB_REPOSITORY is {env_repo!r}, but this script only publishes to {REPOSITORY}"
        )


def main(argv=None, transport=None):
    parser = argparse.ArgumentParser(description="Publish a release to GitHub")
    parser.add_argument("tag", nargs="?", help="release tag, v<version>")
    parser.add_argument("--dist", default="dist", help="directory holding the artifacts")
    args = parser.parse_args(argv)

    try:
        _check_environment_matches()
        if transport is None:
            transport = HttpTransport(_env("GITHUB_TOKEN"))

        tag = args.tag or os.environ.get("GITHUB_REF_NAME")
        if not tag:
            raise PublishError("release tag is required")
        version = tag[1:] if tag.startswith("v") else tag

        artifacts = []
        for suffix in (".zip", ".vsix"):
            name = f"{PACKAGE_NAME}-{version}{suffix}"
            path = os.path.join(args.dist, name)
            if not os.path.exists(path):
                raise PublishError(f"{path} is missing: the packaging step did not produce it")
            with open(path, "rb") as handle:
                artifacts.append((name, handle.read()))

        publish(transport, tag, artifacts, pipeline_commit=os.environ.get("GITHUB_SHA"))
        return 0
    except PublishError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
