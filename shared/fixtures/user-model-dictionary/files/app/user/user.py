from core.model.model import Model


class CustomUser(Model):
    LIMIT: int = 5
    variables: Variables

    @property
    def fields(self):
        return super().fields + [Field("my_field", Geo)]

    def __init__(self):
        super().__init__()
        self.tag: str = None
        self.my_field = None
        self._private = 1

    def helper(self):
        return None
