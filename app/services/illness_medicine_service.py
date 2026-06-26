from app.models.illness_medicine import IllnessMedicine
from app.services.base import BaseService


class IllnessMedicineService(BaseService[IllnessMedicine]):
    model = IllnessMedicine
