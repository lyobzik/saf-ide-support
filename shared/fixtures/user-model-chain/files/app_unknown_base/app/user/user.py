from nlpf_statemachine.override.user import SMUser


class CustomUser(SMUser):
    def __init__(self):
        super().__init__()
        self.smart_geo = None
