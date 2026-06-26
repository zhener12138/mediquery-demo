from app.models.illness_kind import IllnessKind
from app.services.base import BaseService


class IllnessKindService(BaseService[IllnessKind]):
    model = IllnessKind

    def find_list(self):
        return self.all()
