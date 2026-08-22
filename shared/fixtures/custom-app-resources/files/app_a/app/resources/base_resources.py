from smart_kit.resources import SmartAppResources


class BaseResources(SmartAppResources):
    """Базовые ресурсы приложения."""

    def init_actions(self):
        actions["base_action"] = BaseAction
        actions["shared"] = BaseShared

    def init_requirements(self):
        requirements["base_requirement"] = BaseRequirement
