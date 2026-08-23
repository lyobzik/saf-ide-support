#!/usr/bin/env python3
"""Публикация релиза в GitLab: Generic Package Registry и GitLab Release.

Это единственный провайдер-специфичный слой CI: `tools/ci/run.sh` переносим
между хостингами и о GitLab не знает, а здесь всё завязано на его API.

Почему свой скрипт, а не ключевое слово `release:` и не `release-cli`
(объявлен устаревшим): публикация обязана быть идемпотентной, то есть
«прочитать → сравнить → решить», а keyword умеет только создавать и падает на
уже существующем релизе. Повторный запуск после обрыва сети — норма, и он
должен либо довести публикацию до конца, либо внятно отказать, но не создать
второго состояния.

Правила, которые здесь реализованы:

* файл считается опубликованным, только если **все** файлы с таким именем в
  версии имеют один и тот же `file_sha256`, совпадающий с локальным. Реестр
  разрешает дубликаты (запрет — настройка уровня группы, а проект живёт в
  личном namespace), поэтому «первый попавшийся» файл проверять нельзя: какой
  из дублей отдаст ссылка скачивания, реестр не обещает;
* отказ `PUT` разбирается **по состоянию, а не по коду ответа**: запрет дублей
  отвечает `400`, гонка может дать `409`, а разбор текста сообщения означал бы
  зависимость от его формулировки;
* у существующего релиза сверяется точный набор ссылок и коммит — причём
  коммит **тега**, а не коммит пайплайна: тег `smoke` создаётся один раз, а
  смоук запускается с ветки, ушедшей вперёд;
* описание релиза задаётся только при создании: release notes правят руками, и
  повторная публикация не должна их перезаписывать.
"""

import argparse
import fnmatch
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter

# Зависший API не должен держать релизный job до общего таймаута runner'а:
# лучше контролируемый отказ, после которого job перезапускают — публикация
# идемпотентна и повтор безопасен.
HTTP_TIMEOUT = 60

PACKAGE_NAME = "smartapp-dsl"
LINK_TYPE = "package"
PER_PAGE = 100

# Адрес API и проект зафиксированы в коде, а не берутся из окружения.
# Predefined-переменные перекрываются переменными вручную запущенного пайплайна
# — включая `CI_API_V4_URL` и `CI_PROJECT_ID`. Подменённый адрес получил бы оба
# токена и мог бы соврать про защиту тега, а подменённый проект дал бы «доказать»
# защиту на чужом проекте, где она включена. Окружение при этом не
# игнорируется: расхождение с этими константами — отказ, потому что означает
# либо ошибку настройки, либо попытку подмены.
API_URL = "https://gitlab.com/api/v4"
PROJECT_PATH = "lyobzik/saf-ide-support"

# Токен для чтения настроек проекта. `CI_JOB_TOKEN` к `/protected_tags` не
# допускают, а никакой сигнал из окружения доказательством служить не может:
# переменные вручную запущенного пайплайна перекрывают предопределённые,
# включая `CI_COMMIT_REF_PROTECTED`. Поэтому доказательство только одно — ответ
# API, прочитанный токеном с правом читать настройки.
API_TOKEN = "SMARTAPP_API_TOKEN"

SMOKE_TAG = "smoke"
SMOKE_VERSION = "0.0.0-smoke"
SMOKE_DUP_SAME = "0.0.0-smoke-dup-same"
SMOKE_DUP_DIFF = "0.0.0-smoke-dup-diff"


class PublishError(Exception):
    """Осмысленный отказ: состояние реестра не то, которое мы готовы принять."""


class ApiError(PublishError):
    """Неожиданный ответ API или сбой транспорта."""


class HttpTransport:
    """Минимальный клиент REST API GitLab на стандартной библиотеке.

    Стандартной — чтобы образу job'а не нужны были ни curl, ни jq: `urllib`,
    `json` и `hashlib` уже есть в `python:3.12-slim`.
    """

    def __init__(self, api_url, project_id, token, header="JOB-TOKEN"):
        self.base = f"{api_url.rstrip('/')}/projects/{project_ref(project_id)}"
        self.token = token
        self.header = header
        self.puts = 0

    def _request(self, method, path, params=None, data=None, content_type=None):
        url = self.base + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        request = urllib.request.Request(url, data=data, method=method)
        request.add_header(self.header, self.token)
        if content_type:
            request.add_header("Content-Type", content_type)
        try:
            with urllib.request.urlopen(request, timeout=HTTP_TIMEOUT) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise ApiError(f"{method} {path} failed: {error}") from error

    def get_json(self, path, params=None):
        status, body = self._request("GET", path, params=params)
        return status, _decode(body)

    def post_json(self, path, payload):
        status, body = self._request(
            "POST", path, data=json.dumps(payload).encode(), content_type="application/json"
        )
        return status, _decode(body)

    def put_bytes(self, path, data):
        self.puts += 1
        status, body = self._request(
            "PUT", path, data=data, content_type="application/octet-stream"
        )
        return status, (body or b"").decode("utf-8", "replace")


def project_ref(project_id):
    """Идентификатор проекта для URL.

    Проект адресуется путём (`группа/имя`), а не числом, поэтому слэш обязан
    быть закодирован — и в запросах, и в ссылках релиза: незакодированный
    превращает `/projects/группа/имя/...` в чужой путь, по которому ничего нет.
    """
    return urllib.parse.quote(str(project_id), safe="")


def _decode(body):
    if not body:
        return None
    try:
        return json.loads(body)
    except ValueError:
        return None


def iter_pages(transport, path, params=None):
    """Обходит страницы до конца.

    Реестр отдаёт `package_files` порциями, и без обхода второй артефакт просто
    «не найдётся» — а значит будет залит повторно.
    """
    page = 1
    while True:
        query = dict(params or {})
        query.update({"page": page, "per_page": PER_PAGE})
        status, data = transport.get_json(path, query)
        if status != 200:
            raise ApiError(f"GET {path} returned HTTP {status}")
        if not data:
            return
        for item in data:
            yield item
        if len(data) < PER_PAGE:
            return
        page += 1


def find_package(transport, version):
    for package in iter_pages(
        transport, "/packages", {"package_type": "generic", "package_name": PACKAGE_NAME}
    ):
        if package.get("name") == PACKAGE_NAME and package.get("version") == version:
            return package
    return None


def files_of(transport, version, file_name):
    package = find_package(transport, version)
    if package is None:
        return []
    files = iter_pages(transport, f"/packages/{package['id']}/package_files")
    return [item for item in files if item.get("file_name") == file_name]


def digests_of(files):
    return Counter(item.get("file_sha256") for item in files)


def file_state(transport, version, file_name, digest):
    """`absent` | `present` | `mismatch` — состояние файла в версии."""
    files = files_of(transport, version, file_name)
    if not files:
        return "absent"
    found = set(digests_of(files))
    return "present" if found == {digest} else "mismatch"


def upload_path(version, file_name):
    return f"/packages/generic/{PACKAGE_NAME}/{version}/{file_name}"


def ensure_file(transport, version, file_name, content):
    """Возвращает `uploaded` или `present`; иначе бросает."""
    digest = hashlib.sha256(content).hexdigest()
    state = file_state(transport, version, file_name, digest)
    if state == "present":
        return "present"
    if state == "mismatch":
        raise PublishError(
            f"{file_name} already published in {version} with a different checksum: "
            "the tag was moved or the build is not reproducible"
        )

    status, text = transport.put_bytes(upload_path(version, file_name), content)
    if status in (200, 201):
        return "uploaded"

    # Решает состояние, а не код: запрет дублей отвечает 400, гонка — 409.
    if file_state(transport, version, file_name, digest) == "present":
        return "present"
    raise ApiError(f"upload of {file_name} failed: HTTP {status}: {text}")


def link_set(api_url, project_id, version, file_names):
    return {
        (
            name,
            f"{api_url.rstrip('/')}/projects/{project_ref(project_id)}/packages/generic/"
            f"{PACKAGE_NAME}/{version}/{name}",
            LINK_TYPE,
        )
        for name in file_names
    }


def links_of(release):
    assets = (release or {}).get("assets") or {}
    return {
        (link.get("name"), link.get("url"), link.get("link_type"))
        for link in assets.get("links") or []
    }


def tag_protection(transport, tag):
    """`protected` | `unprotected` | `unknown`.

    Неизменность релизного тега обеспечивает не этот скрипт, а настройка
    проекта: атомарной привязки релиза к коммиту в API нет, поэтому от
    перестановки тега защищает только запрет на уровне GitLab. Скрипт обязан
    убедиться, что запрет включён, — «в документации написано» и «включено» это
    разные вещи.

    `unknown` (нет прав прочитать список) обрабатывается fail-closed: см.
    `publish`. Подтвердить защиту переменной окружения нельзя ни в каком виде —
    переменные вручную запущенного пайплайна перекрывают предопределённые.
    """
    patterns = []
    page = 1
    while True:
        # Страницы обходятся до конца: правило `v*` может оказаться и на второй,
        # и тогда «не нашли» означало бы «не защищён» — отказ на ровном месте.
        status, data = transport.get_json("/protected_tags", {"page": page, "per_page": PER_PAGE})
        if status != 200 or not isinstance(data, list):
            return "unknown"
        patterns.extend(item.get("name") for item in data if item.get("name"))
        if len(data) < PER_PAGE:
            break
        page += 1
    return "protected" if any(fnmatch.fnmatchcase(tag, p) for p in patterns) else "unprotected"


def tag_commit(transport, tag):
    status, data = transport.get_json(f"/repository/tags/{urllib.parse.quote(tag, safe='')}")
    if status == 404:
        return None
    if status != 200:
        raise ApiError(f"cannot read tag {tag}: HTTP {status}")
    return ((data or {}).get("commit") or {}).get("id")


def check_release(release, expected_links, expected_commit):
    actual = links_of(release)
    if actual != expected_links:
        missing = expected_links - actual
        extra = actual - expected_links
        raise PublishError(
            "release assets differ from what this build publishes: "
            f"missing={sorted(missing)} unexpected={sorted(extra)}"
        )
    commit = ((release or {}).get("commit") or {}).get("id")
    if expected_commit is not None and commit != expected_commit:
        raise PublishError(
            f"release was created for commit {commit}, but the tag now points at "
            f"{expected_commit}: the tag was moved"
        )


def ensure_release(transport, tag, expected_links, expected_commit, description=None, ref=None):
    """Возвращает `created` или `present`; иначе бросает."""
    quoted = urllib.parse.quote(tag, safe="")
    status, data = transport.get_json(f"/releases/{quoted}")
    if status == 200:
        if expected_commit is None:
            # Релиз есть, а тега нет: сверить его не с чем, и «зелено» тут было
            # бы худшим ответом — состояние сломано и требует человека.
            raise PublishError(
                f"release {tag} exists, but the tag does not: nothing to verify the release against"
            )
        check_release(data, expected_links, expected_commit)
        return "present"
    if status != 404:
        raise ApiError(f"cannot read release {tag}: HTTP {status}")

    payload = {
        "tag_name": tag,
        "name": tag,
        "description": description or f"Release {tag}",
        "assets": {
            "links": [
                {"name": name, "url": url, "link_type": link_type}
                for name, url, link_type in sorted(expected_links)
            ]
        },
    }
    if ref:
        payload["ref"] = ref
    status, data = transport.post_json("/releases", payload)
    if status in (200, 201):
        # Ответ API проверяется сразу, а не при следующем запуске: между чтением
        # коммита тега и созданием релиза тег могли переставить, и тогда релиз
        # привязан к одному коду, а артефакты собраны из другого. Помешать этому
        # на стороне скрипта нечем (атомарной привязки в API нет) —
        # неизменность даёт protected tag, а здесь её нарушение обязано
        # немедленно всплыть.
        created = data
        if not ((created or {}).get("commit") or {}).get("id"):
            status, created = transport.get_json(f"/releases/{quoted}")
            if status != 200:
                raise ApiError(f"release {tag} was created but cannot be read back: HTTP {status}")
        check_release(created, expected_links, expected_commit or ref)
        return "created"

    status, data = transport.get_json(f"/releases/{quoted}")
    if status == 200:
        check_release(data, expected_links, expected_commit)
        return "present"
    raise ApiError(f"cannot create release {tag}: HTTP {status}")


def publish(
    transport,
    api_url,
    project_id,
    version,
    tag,
    artifacts,
    pipeline_commit=None,
    ref=None,
    require_protected_tag=False,
    protection_transport=None,
):
    """Полная публикация: файлы в реестр, затем Release со ссылками на них."""
    if require_protected_tag:
        # Проверяется до загрузки: незачем оставлять в реестре пакеты релиза,
        # который мы всё равно откажемся выпускать.
        state = tag_protection(protection_transport or transport, tag)
        if state == "unprotected":
            # Отрицательный ответ API — авторитетный: перекрыть его не может
            # ничто, включая наличие защищённой переменной.
            raise PublishError(
                f"tag {tag} is not covered by any protected tag rule. Releases rely on the tag "
                "being immutable: without protection it can be moved between the build and the "
                "release, and the artifacts would describe a different commit. Protect `v*` in "
                "Settings -> Repository -> Protected tags first."
            )
        if state == "unknown":
            raise PublishError(
                "cannot read the list of protected tags, so the tag cannot be proven immutable. "
                f"CI_JOB_TOKEN is not allowed there: add a project access token with the read_api "
                f"scope as the protected, masked variable {API_TOKEN}. Publishing without that "
                "proof is refused, because an unprotected tag can be moved between the build and "
                "the release."
            )

    for file_name, content in artifacts:
        print(f"    {file_name}: {ensure_file(transport, version, file_name, content)}")

    commit = tag_commit(transport, tag)
    if commit is None and not ref:
        raise PublishError(f"tag {tag} does not exist")
    if pipeline_commit and commit and commit != pipeline_commit:
        raise PublishError(
            f"tag {tag} points at {commit}, but the pipeline runs on {pipeline_commit}: "
            "the tag was moved after this pipeline started"
        )

    links = link_set(api_url, project_id, version, [name for name, _ in artifacts])
    result = ensure_release(transport, tag, links, commit, ref=ref)
    print(f"    release {tag}: {result}")
    return result


# --- смоук против настоящего API -------------------------------------------
#
# Фейковый транспорт в тестах проверяет алгоритм, но не GitLab: ни формат
# ответа `/packages`, ни коды отказов реестра, ни работу `JOB-TOKEN`, ни приём
# `assets.links`. Без этого шага первый настоящий релиз стал бы одновременно
# интеграционным тестом API — на артефактах, которых уже кто-то ждёт.
#
# Мусор в реестре постоянен: три версии и один релиз, сколько бы раз смоук ни
# запускали. Подготовка дублей выполняется один раз за всё время — при точном
# совпадении состояния прямые `PUT` пропускаются.

SMOKE_A = b"smartapp-dsl smoke payload A\n"
SMOKE_B = b"smartapp-dsl smoke payload B\n"

PASS, FAIL, NA = "PASS", "FAIL", "N/A"


def version_files(transport, version):
    package = find_package(transport, version)
    if package is None:
        return []
    return list(iter_pages(transport, f"/packages/{package['id']}/package_files"))


def prepare_duplicates(transport, version, file_name, contents):
    """`ready` | `prepared` | `denied` | `dirty`.

    Дубль нельзя получить через обычную публикацию — она увидит тот же хеш и
    не станет грузить, — поэтому состояние создаётся прямыми `PUT` мимо
    алгоритма. Перед пропуском подготовки состояние сверяется точно: иначе
    прерванный прогон оставит половину, а следующие запуски молча проверят не
    тот сценарий.
    """
    by_digest = {}
    for content in contents:
        by_digest.setdefault(hashlib.sha256(content).hexdigest(), content)
    wanted = Counter(hashlib.sha256(content).hexdigest() for content in contents)

    files = version_files(transport, version)
    if any(item.get("file_name") != file_name for item in files):
        return "dirty"
    actual = digests_of(files)
    if actual == wanted:
        return "ready"
    if actual - wanted:
        return "dirty"

    for digest, count in (wanted - actual).items():
        for _ in range(count):
            status, text = transport.put_bytes(upload_path(version, file_name), by_digest[digest])
            if status in (200, 201):
                continue
            if 400 <= status < 500:
                # Реестр в режиме Deny — дубликат создать не дадут. Это не
                # авария, а единственный доступный способ узнать режим: страницы
                # групповых настроек у личного namespace нет.
                return "denied"
            raise ApiError(f"smoke upload failed: HTTP {status}: {text}")
    return "prepared"


def _duplicate_scenario(transport, version, contents, expect_mismatch):
    file_name = f"{PACKAGE_NAME}-{version}.zip"
    state = prepare_duplicates(transport, version, file_name, contents)
    if state == "denied":
        return NA, f"{version}: registry denies duplicates, scenario not applicable"
    if state == "dirty":
        return FAIL, f"{version}: unexpected package files, clean the version manually"

    before = transport.puts
    try:
        result = ensure_file(transport, version, file_name, contents[0])
    except PublishError as error:
        if expect_mismatch:
            return PASS, f"{version}: refused as expected ({error})"
        return FAIL, f"{version}: unexpected refusal ({error})"
    if expect_mismatch:
        return FAIL, f"{version}: mismatched duplicates accepted as '{result}' — the check is broken"
    if transport.puts != before:
        return FAIL, f"{version}: identical duplicates triggered an upload"
    return PASS, f"{version}: identical duplicates accepted without upload"


def smoke(transport, api_url, project_id, ref, protection_transport=None):
    """Прогон сценариев против настоящего API; код возврата — их итог."""
    results = []

    artifacts = [
        (f"{PACKAGE_NAME}-{SMOKE_VERSION}.zip", SMOKE_A),
        (f"{PACKAGE_NAME}-{SMOKE_VERSION}.vsix", SMOKE_B),
    ]
    try:
        print("--> publish")
        publish(transport, api_url, project_id, SMOKE_VERSION, SMOKE_TAG, artifacts, ref=ref)
        results.append((PASS, "publish: package files and release are in place"))

        print("--> publish again")
        before = transport.puts
        publish(transport, api_url, project_id, SMOKE_VERSION, SMOKE_TAG, artifacts, ref=ref)
        if transport.puts == before:
            results.append((PASS, "repeat: nothing uploaded, release untouched"))
        else:
            results.append((FAIL, "repeat: uploaded again — publication is not idempotent"))
    except PublishError as error:
        results.append((FAIL, f"publish: {error}"))

    state = tag_protection(protection_transport or transport, "v0.0.0")
    if state == "protected":
        results.append((PASS, "protected tags: release tags like v0.0.0 are covered"))
    elif state == "unprotected":
        results.append((FAIL, "protected tags: `v*` is NOT protected — releases are unsafe"))
    else:
        results.append((NA, "protected tags: this token cannot read the setting"))

    for version, contents, expect_mismatch in (
        (SMOKE_DUP_SAME, [SMOKE_A, SMOKE_A], False),
        (SMOKE_DUP_DIFF, [SMOKE_A, SMOKE_B], True),
    ):
        print(f"--> duplicates {version}")
        try:
            results.append(_duplicate_scenario(transport, version, contents, expect_mismatch))
        except ApiError as error:
            results.append((FAIL, f"{version}: {error}"))

    print()
    for verdict, message in results:
        print(f"{verdict:4} {message}")
    failed = sum(1 for verdict, _ in results if verdict == FAIL)
    print(f"\n{len(results)} scenarios, {failed} failed")
    return 1 if failed else 0


def _check_environment_matches():
    """Сверяет окружение с зафиксированными адресом и проектом.

    Значения из окружения не используются — но и молча игнорировать их нельзя:
    расхождение означает либо что скрипт запущен не там, где думает, либо
    попытку подмены, и человек должен об этом узнать.
    """
    # Совпадение требуется точное, а пустое или отсутствующее значение — такой
    # же отказ, как чужое: иначе guard выключается пустой pipeline variable.
    # В настоящем job'е обе переменные выставляет GitLab, поэтому их отсутствие
    # само по себе означает, что запуск идёт не там, где скрипт думает.
    env_url = os.environ.get("CI_API_V4_URL", "")
    if env_url.rstrip("/") != API_URL:
        raise PublishError(
            f"CI_API_V4_URL is {env_url!r}, but this script only publishes to {API_URL}. "
            "The API address is pinned in the source because pipeline variables can override "
            "predefined ones, and a redirected API would receive both tokens."
        )
    env_path = os.environ.get("CI_PROJECT_PATH", "")
    if env_path != PROJECT_PATH:
        raise PublishError(
            f"CI_PROJECT_PATH is {env_path!r}, but this script only publishes to {PROJECT_PATH}."
        )


def _env(name):
    value = os.environ.get(name)
    if not value:
        raise PublishError(f"{name} is not set: this script runs inside GitLab CI")
    return value


def main(argv=None, transport=None):
    """Точка входа.

    `transport` подменяется тестами: связывание окружения с проверками — то
    место, где регрессия тише всего (переменные читаются здесь, а не в
    `publish`), поэтому оно тоже должно проверяться, а не только логика.
    """
    parser = argparse.ArgumentParser(description="Publish a release to GitLab")
    parser.add_argument("tag", nargs="?", help="release tag, v<version>")
    parser.add_argument("--smoke", action="store_true", help="run scenarios against the real API")
    parser.add_argument("--dist", default="dist", help="directory holding the artifacts")
    args = parser.parse_args(argv)

    try:
        api_url, project_id = API_URL, PROJECT_PATH
        _check_environment_matches()
        if transport is None:
            transport = HttpTransport(api_url, project_id, _env("CI_JOB_TOKEN"))

        # Права на чтение настроек проекта — у отдельного токена, если он задан.
        api_token = os.environ.get(API_TOKEN)
        protection_transport = (
            HttpTransport(api_url, project_id, api_token, header="PRIVATE-TOKEN")
            if api_token
            else None
        )

        if args.smoke:
            return smoke(
                transport,
                api_url,
                project_id,
                ref=_env("CI_COMMIT_SHA"),
                protection_transport=protection_transport,
            )

        tag = args.tag or os.environ.get("CI_COMMIT_TAG")
        if not tag:
            raise PublishError("release tag is required")
        version = tag[1:] if tag.startswith("v") else tag

        artifacts = []
        for suffix in (".zip", ".vsix"):
            name = f"{PACKAGE_NAME}-{version}{suffix}"
            path = os.path.join(args.dist, name)
            if not os.path.exists(path):
                raise PublishError(f"{path} is missing: the packaging job did not produce it")
            with open(path, "rb") as handle:
                artifacts.append((name, handle.read()))

        publish(
            transport,
            api_url,
            project_id,
            version,
            tag,
            artifacts,
            # `CI_COMMIT_SHA` — вспомогательная сверка: значение приходит из
            # окружения, а его в ручном пайплайне можно переопределить. Основную
            # защиту даёт сверка коммита релиза с коммитом тега — оба из API.
            pipeline_commit=os.environ.get("CI_COMMIT_SHA"),
            require_protected_tag=True,
            protection_transport=protection_transport,
        )
        return 0
    except PublishError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
