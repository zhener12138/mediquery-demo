from typing import List, Optional, Dict
from math import ceil
from sqlalchemy import or_

from app.models.medicine import Medicine
from app.services.base import BaseService
from app.utils.helpers import not_empty


class MedicineService(BaseService[Medicine]):
    model = Medicine

    def get_medicine_list(self, name_value: Optional[str] = None, page: int = 1) -> Dict:
        page_size = 9
        q = self.db.query(Medicine)

        if not_empty(name_value):
            q = q.filter(
                or_(
                    Medicine.medicine_name.like(f"%{name_value}%"),
                    Medicine.keyword.like(f"%{name_value}%"),
                    Medicine.medicine_effect.like(f"%{name_value}%"),
                )
            )

        total = q.count()
        offset = (page - 1) * page_size
        medicines = q.offset(offset).limit(page_size).all()

        size = max(1, ceil(total / page_size)) if total > 0 else 1
        return {"medicineList": medicines, "size": size}
