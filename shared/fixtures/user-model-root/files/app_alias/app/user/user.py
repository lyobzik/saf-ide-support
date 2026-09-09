from core.model.model import Model


class CustomUser(Model):
    @property
    def fields(self):
        return super().fields + [Field("smart_geo", Geo)]
