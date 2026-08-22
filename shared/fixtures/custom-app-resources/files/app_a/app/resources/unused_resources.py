from app.resources.base_resources import BaseResources


class UnusedResources(BaseResources):
    def init_actions(self):
        actions["unused_action"] = UnusedAction
