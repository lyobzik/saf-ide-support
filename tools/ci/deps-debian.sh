#!/usr/bin/env sh
# Системные пакеты для интеграционного теста VS Code в голом Debian-образе
# (`node:22-bookworm` у GitLab): настоящий редактор — это Electron, которому
# нужны X11-библиотеки и виртуальный дисплей.
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
    libnss3 \
    libgbm1 \
    libasound2 \
    libgtk-3-0 \
    libx11-xcb1 \
    libxss1 \
    libsecret-1-0
