from scenarios.user.parametrizer import Parametrizer


class CustomParametrizer(Parametrizer):
    def _get_user_data(self, tpr=None):
        data = super()._get_user_data(tpr)
        data["user"] = self._user
        return data
