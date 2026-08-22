from smart_kit.resources import SmartAppResources


class CustomAppResources(SmartAppResources):
    """Ресурсы приложения: RESOURCES в docstring сканер обязан пропустить."""

    def init_actions(self):
        super().init_actions()
        actions["custom_action"] = CustomAction
