#!/usr/bin/env sh
# Сверяет версию артефактов: между двумя манифестами и, если сборка идёт по
# тегу, между тегом и манифестами.
#
# Версия живёт в двух манифестах: `version` в idea-plugin/build.gradle.kts и
# `version` в vscode-extension/package.json. Свести их в один источник нельзя —
# npm не читает gradle.properties, а переписывать package.json генератором
# значит ломать package-lock.json. Поэтому расхождение ловится проверкой: иначе
# релиз уедет двумя разными номерами, и понять, какая пара артефактов
# совместима, будет уже негде.
#
# Тег — третье место, где написана версия. Формат релизного тега: `v<version>`,
# например `v0.1.0`. Без этой проверки тег `v0.2.0` спокойно собрал бы артефакты
# `0.1.0`, и в релизе оказалась бы пара файлов, чей номер противоречит имени
# тега.
#
# Использование: check-version.sh [тег]
# Тег берётся из аргумента, иначе из CI_COMMIT_TAG; пусто — проверяются только
# манифесты.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
tag=${1:-${CI_COMMIT_TAG:-}}

plugin_version=$(sed -n 's/^version = "\([^"]*\)"$/\1/p' "$root/idea-plugin/build.gradle.kts" | head -1)
extension_version=$(sed -n 's/^  "version": "\([^"]*\)",$/\1/p' "$root/vscode-extension/package.json" | head -1)

if [ -z "$plugin_version" ]; then
  echo "Cannot read version from idea-plugin/build.gradle.kts" >&2
  exit 1
fi

if [ -z "$extension_version" ]; then
  echo "Cannot read version from vscode-extension/package.json" >&2
  exit 1
fi

if [ "$plugin_version" != "$extension_version" ]; then
  echo "Version mismatch: idea-plugin $plugin_version, vscode-extension $extension_version" >&2
  echo "Both manifests must carry the same version." >&2
  exit 1
fi

# Теги, не похожие на версию (например `sandbox`), релизными не считаются и
# упаковку не запускают — их проверять нечем и незачем.
case "$tag" in
  "")
    ;;
  v[0-9]*|[0-9]*)
    if [ "$tag" != "v$plugin_version" ]; then
      echo "Tag $tag does not match version $plugin_version" >&2
      echo "Release tags are named v<version>, so this one must be v$plugin_version." >&2
      exit 1
    fi
    echo "Version $plugin_version (idea-plugin, vscode-extension, tag $tag)"
    exit 0
    ;;
esac

echo "Version $plugin_version (idea-plugin, vscode-extension)"
