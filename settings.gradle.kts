rootProject.name = "smartapp-dsl"

// Плагин для IntelliJ-платформы — единственный Gradle-модуль монорепозитория.
// VS Code-расширение живёт в `vscode-extension/` и собирается npm, а не Gradle:
// системы сборки намеренно не объединяются, общий gate — CI-пайплайн.
include(":idea-plugin")
