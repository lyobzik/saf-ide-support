from smart_kit.resources import SmartAppResources

from app.basic_entities.actions import CustomAction


class CustomAppResources(SmartAppResources):
    def init_actions(self):
        super().init_actions()
        actions["custom_action"] = CustomAction
