#!/usr/bin/env sh
# Системные пакеты для интеграционного теста VS Code в голом Debian-образе
# (`node:22-bookworm` у GitLab): настоящий редактор — это Electron, которому
# нужны X11-библиотеки и виртуальный дисплей.
#
# Список не выдуман: это зависимости официального `.deb`-пакета VS Code
# (`libnotify4`, `libnss3`, `libxkbfile1`, `libgtk-3-0`, `libxss1`,
# `libsecret-1-0`, `libgbm1`, `libasound2`, `xdg-utils`) плюс сам `xvfb` и
# `xauth`. У списка должен быть источник, иначе он превращается в набор
# пакетов, добавленных по одному после каждого падения.
#
# `xauth` нужен не Electron, а `xvfb-run`: без него он падает с
# `xauth command not found`, и выглядит это как поломка теста, а не нехватка
# пакета. В зависимостях xvfb его нет — только в рекомендациях, а
# `--no-install-recommends` их не ставит.
#
# Скрипт рассчитан на контейнер, где job идёт от root, и намеренно не умеет
# ни `sudo`, ни другие дистрибутивы: GitHub интеграционный тест не гоняет
# (см. план), а «умный» скрипт, ветвящийся по окружению, — это способ получить
# разное поведение там, где нужно одинаковое.
set -eu

if [ "$(id -u)" -ne 0 ]; then
    echo "deps-debian.sh expects a root container (no sudo fallback by design)" >&2
    exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
    xvfb \
    xauth \
    libnotify4 \
    libnss3 \
    libxkbfile1 \
    libgtk-3-0 \
    libxss1 \
    libsecret-1-0 \
    libgbm1 \
    libasound2 \
    xdg-utils
