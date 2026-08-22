from smart_kit.resources import SmartAppResources


class CustomAppResources(SmartAppResources):
    def init_actions(self):
        actions["nested_action"] = NestedAction
