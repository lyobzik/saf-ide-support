from app.resources.base_resources import BaseResources


class CustomAppResources(BaseResources):
    """Ресурсы приложения: слово RESOURCES в docstring сканер обязан пропустить."""

    def init_actions(self):
        super().init_actions()
        actions["custom_action"] = CustomAction
        actions["shared"] = CustomShared

    def init_requirements(self):
        requirements["custom_requirement"] = CustomRequirement
