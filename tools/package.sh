#!/usr/bin/env bash
# Собирает оба артефакта релиза и кладёт их в `dist/`:
#
#   dist/smartapp-dsl-<версия>.zip    — плагин IntelliJ-платформы
#   dist/smartapp-dsl-<версия>.vsix   — расширение VS Code
#
# Это тонкая обёртка, а не общая система сборки: скрипт по очереди вызывает
# Gradle и npm, каждый из которых остаётся самостоятельным. Общий gate проекта
# по-прежнему CI, а не этот файл.
set -euo pipefail

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"

# Версии обоих манифестов должны совпасть до сборки, а не после.
sh tools/check-version.sh

if [ -z "${JAVA_HOME:-}" ] && ! command -v java >/dev/null 2>&1; then
  echo "No JDK found: export JAVA_HOME first, for example" >&2
  echo '  export JAVA_HOME="/Applications/GIGA IDE CE 2025.1.app/Contents/jbr/Contents/Home"' >&2
  exit 1
fi

echo "==> idea-plugin"
# На машине разработчика платформа берётся из установленной IDE (умолчание
# gradle.properties), в CI и на любой машине без неё — из maven: без этого
# скрипт уходит в `ideSource=local` и падает на несуществующем пути к IDE.
# shellcheck disable=SC2086
./gradlew ${GRADLE_ARGS:-} :idea-plugin:packagePlugin "$@"

echo "==> vscode-extension"
if [ ! -d vscode-extension/node_modules ]; then
  npm --prefix vscode-extension ci
fi
npm --prefix vscode-extension run package

version=$(sed -n 's/^version = "\([^"]*\)"$/\1/p' idea-plugin/build.gradle.kts | head -1)
echo "==> done"
ls -l "dist/smartapp-dsl-$version.zip" "dist/smartapp-dsl-$version.vsix"
