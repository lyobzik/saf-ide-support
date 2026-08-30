#!/usr/bin/env sh
# Общий слой CI: какие команды и с какими флагами составляют шаг проверки или
# сборки. Один и тот же файл вызывают оба хостинга (GitLab и GitHub) и человек
# локально — поэтому здесь нет ни кэшей, ни артефактов, ни условий «на какой
# ветке», ни единой переменной хостинга: тег приходит аргументом.
#
#   tools/ci/run.sh <шаг> [аргумент…] [<шаг> …]
#
# Шагов можно передать несколько — они выполняются подряд в одном процессе, то
# есть в одном контейнере и с одним кэшем Gradle. Ради этого и сделано:
# `run.sh test:idea package:idea` на релизном теге скачивает платформу один раз,
# а не дважды.
#
# Аргументом считается токен, не совпадающий с именем шага; он достаётся
# ближайшему шагу слева.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$root"

# Источник платформы IntelliJ: на runner'ах локальной IDE нет ни у одного
# хостинга. Локально переопределяется окружением — причём `GRADLE_ARGS=`
# (пустое, но заданное) обязано означать «никаких флагов, бери умолчание из
# gradle.properties», поэтому подстановка `${VAR-default}`, а не `${VAR:-default}`:
# вторая заменила бы и пустое значение, и обещание README не выполнялось бы.
GRADLE_ARGS=${GRADLE_ARGS--PideSource=maven}

# Отчёт vitest в формате JUnit — чтобы падения было видно в интерфейсе, а не
# только в логе. Флаг живёт здесь, а не в package.json: `npm run test:unit`
# остаётся человеческой командой.
VITEST_REPORT=build/test-results/vitest.xml

is_step() {
    case $1 in
        contract:keywords|contract:version|contract:schema|test:publish|\
        test:idea|test:vscode|test:vscode:int|package:idea|package:vscode) return 0 ;;
        *) return 1 ;;
    esac
}

# Сколько аргументов принимает шаг. Лишние — это опечатка (в CI чаще всего
# перепутанное имя шага), и молча их игнорировать нельзя: проверка осталась бы
# зелёной, проверив не то.
max_args() {
    case $1 in
        contract:version) echo 1 ;;
        *) echo 0 ;;
    esac
}

check_arity() {
    limit=$(max_args "$1")
    if [ "$2" -gt "$limit" ]; then
        echo "Step '$1' takes at most $limit argument(s), got $2" >&2
        exit 2
    fi
}

# Первый проход: пересчитать аргументы каждого шага и упасть на лишних.
# Считаются именно токены, а не непустые строки: пустой тег — валидный
# аргумент (на ветке хостинг передаёт именно его), и склеивать аргументы в одну
# строку нельзя — пустые в ней исчезают, а `contract:version "" extra` выглядел
# бы как один аргумент.
validate() {
    step=""
    count=0
    for token in "$@"; do
        if is_step "$token"; then
            if [ -n "$step" ]; then
                check_arity "$step" "$count"
            fi
            step=$token
            count=0
        else
            count=$((count + 1))
        fi
    done
    if [ -n "$step" ]; then
        check_arity "$step" "$count"
    fi
}

# Второй проход: выполнение. Аргумент передаётся отдельным позиционным
# параметром, поэтому пустая строка доезжает до шага как пустая строка.
# Больше одного аргумента сюда не приходит — это гарантирует validate.
dispatch() {
    step=$1
    shift
    if [ $# -gt 0 ] && ! is_step "$1"; then
        run_step "$step" "$1"
        shift
    else
        run_step "$step"
    fi
    if [ $# -gt 0 ]; then
        dispatch "$@"
    fi
}

npm_install() {
    npm --prefix vscode-extension ci
}

gradlew() {
    # shellcheck disable=SC2086
    ./gradlew $GRADLE_ARGS "$@"
}

run_step() {
    step=$1
    shift
    echo "==> $step"
    case $step in
        contract:keywords)
            # Словарь обязан соответствовать vendored-исходнику фреймворка.
            python3 tools/generate_keywords.py --check
            ;;
        contract:version)
            # Версия живёт в двух манифестах и в имени тега; тег приходит
            # аргументом, потому что называется он у хостингов по-разному.
            sh tools/check-version.sh "${1:-}"
            ;;
        contract:schema)
            npm_install
            npm --prefix vscode-extension run verify:contract
            ;;
        test:publish)
            # Алгоритм публикации на фейковом транспорте: сети не требует и
            # поэтому гоняется на обоих хостингах.
            #
            # Кэш байт-кода уводится в отдельный каталог на каждый прогон.
            # `PYTHONDONTWRITEBYTECODE` для этого не годится: он запрещает
            # запись, но не чтение уже лежащего `__pycache__`, а тот считает
            # исходник неизменным при совпадении mtime (секундная точность) и
            # размера — то есть правка той же длины, сделанная в ту же секунду,
            # исполнится старым байт-кодом. На мутационных прогонах это даёт
            # ложный результат в обе стороны, причём молча.
            cache=$(mktemp -d)
            status=0
            PYTHONPYCACHEPREFIX="$cache" \
                python3 -m unittest discover -s tools/ci -p 'test_publish_*.py' || status=$?
            rm -rf "$cache"
            [ "$status" -eq 0 ] || exit "$status"
            ;;
        test:idea)
            # Порядок задач — по возрастанию цены: снимок контракта дешевле
            # тестов, тесты дешевле верификатора. Все три в одном шаге, потому
            # что каждому нужна та же платформа.
            gradlew -Djava.awt.headless=true \
                :idea-plugin:exportRules --check \
                :idea-plugin:test \
                :idea-plugin:verifyPlugin
            ;;
        test:vscode)
            npm_install
            # eslint держит контрактное правило «в src/core нет DSL-литералов и
            # импорта shared/» — это проверка контракта, а не косметика.
            npm --prefix vscode-extension run lint
            npm --prefix vscode-extension run typecheck
            mkdir -p "vscode-extension/$(dirname "$VITEST_REPORT")"
            npm --prefix vscode-extension run test:unit -- \
                --reporter=default --reporter=junit \
                --outputFile.junit="$VITEST_REPORT"
            ;;
        test:vscode:int)
            npm_install
            # Настоящий VS Code: регистрация провайдеров и активация расширения.
            # Без дисплея нужен xvfb; на машине разработчика дисплей есть.
            if [ -z "${DISPLAY:-}" ] && command -v xvfb-run >/dev/null 2>&1; then
                xvfb-run -a npm --prefix vscode-extension run test:integration
            else
                npm --prefix vscode-extension run test:integration
            fi
            ;;
        package:idea)
            gradlew :idea-plugin:packagePlugin
            ;;
        package:vscode)
            npm_install
            # Состав .vsix проверяет сам scripts/package.mjs.
            npm --prefix vscode-extension run package
            ;;
    esac
}

if [ $# -eq 0 ]; then
    echo "Usage: tools/ci/run.sh <step> [arg…] [<step> …]" >&2
    exit 2
fi

if ! is_step "$1"; then
    echo "Unknown step '$1'" >&2
    exit 2
fi

validate "$@"
dispatch "$@"
