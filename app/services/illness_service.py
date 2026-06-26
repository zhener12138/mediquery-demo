from typing import List, Optional, Dict
from math import ceil
from sqlalchemy.orm import Session
from sqlalchemy import or_

from app.models.illness import Illness
from app.models.illness_kind import IllnessKind
from app.models.illness_medicine import IllnessMedicine
from app.models.medicine import Medicine
from app.models.pageview import Pageview
from app.services.base import BaseService
from app.utils.helpers import not_empty


class IllnessService(BaseService[Illness]):
    model = Illness

    def find_illness(self, kind: Optional[int] = None, illness_name: Optional[str] = None, page: int = 1) -> Dict:
        page_size = 9
        q = self.db.query(Illness)
        q = q.order_by(Illness.create_time.desc())

        if not_empty(illness_name):
            q = q.filter(
                or_(
                    Illness.illness_name.like(f"%{illness_name}%"),
                    Illness.include_reason.like(f"%{illness_name}%"),
                    Illness.illness_symptom.like(f"%{illness_name}%"),
                    Illness.special_symptom.like(f"%{illness_name}%"),
                )
            )

        if kind is not None:
            q = q.filter(Illness.kind_id == kind)

        total = q.count()
        offset = (page - 1) * page_size
        illnesses = q.offset(offset).limit(page_size).all()

        results = []
        for ill in illnesses:
            d = {
                "id": ill.id,
                "kind_id": ill.kind_id,
                "illness_name": ill.illness_name,
                "include_reason": ill.include_reason,
                "illness_symptom": ill.illness_symptom,
                "special_symptom": ill.special_symptom,
                "create_time": ill.create_time,
                "update_time": ill.update_time,
            }
            pv = self.db.query(Pageview).filter(Pageview.illness_id == ill.id).first()
            d["pageview"] = pv.pageviews if pv else 0
            d["kindName"] = "暂无归属类"
            if ill.kind_id:
                kind_obj = self.db.query(IllnessKind).filter(IllnessKind.id == ill.kind_id).first()
                if kind_obj:
                    d["kindName"] = kind_obj.name
            results.append(d)

        size = max(1, ceil(total / page_size)) if total > 0 else 1
        return {"illness": results, "size": size}

    def find_illness_one(self, illness_id: int) -> Dict:
        illness = self.get(illness_id)
        if not illness:
            return {"illness": None, "medicine": []}

        # Update pageview
        pv = self.db.query(Pageview).filter(Pageview.illness_id == illness_id).first()
        if not pv:
            pv = Pageview(pageviews=1, illness_id=illness_id)
            self.db.add(pv)
        else:
            pv.pageviews += 1
        self.db.commit()

        # Find related medicines
        ims = (
            self.db.query(IllnessMedicine)
            .filter(IllnessMedicine.illness_id == illness_id)
            .all()
        )
        medicines = []
        for im in ims:
            med = self.db.query(Medicine).filter(Medicine.id == im.medicine_id).first()
            if med:
                medicines.append({
                    "id": med.id,
                    "medicine_name": med.medicine_name,
                    "keyword": med.keyword,
                    "medicine_effect": med.medicine_effect,
                    "medicine_brand": med.medicine_brand,
                    "interaction": med.interaction,
                    "taboo": med.taboo,
                    "us_age": med.us_age,
                    "medicine_type": med.medicine_type,
                    "img_path": med.img_path,
                    "medicine_price": str(med.medicine_price) if med.medicine_price else None,
                })

        return {"illness": illness, "medicine": medicines}

    def global_search(self, name_value: str) -> List[Illness]:
        """Search illnesses by comma-separated keywords across name, symptom, special_symptom."""
        keywords = [s.strip() for s in name_value.replace("，", ",").split(",") if s.strip()]
        result_set = set()
        for kw in keywords:
            for field in [Illness.illness_name, Illness.special_symptom, Illness.illness_symptom]:
                matches = self.db.query(Illness).filter(field.like(f"%{kw}%")).all()
                result_set.update(matches)
        return list(result_set)
