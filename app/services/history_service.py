from typing import List, Dict, Optional
from sqlalchemy import desc

from app.models.history import History
from app.models.illness_kind import IllnessKind
from app.services.base import BaseService


class HistoryService(BaseService[History]):
    model = History

    def insert_one(self, user_id: int, operate_type: int, keyword: str) -> bool:
        h = History(user_id=user_id, keyword=keyword, operate_type=operate_type)
        self.db.add(h)
        self.db.commit()
        return True

    def find_list(self, user_id: int) -> List[Dict]:
        records = (
            self.db.query(History)
            .filter(History.user_id == user_id)
            .order_by(desc(History.create_time))
            .all()
        )
        # Deduplicate by keyword, keep most recent
        seen = set()
        deduped = []
        for r in records:
            if r.keyword not in seen:
                seen.add(r.keyword)
                deduped.append(r)
        deduped.sort(key=lambda r: r.create_time, reverse=True)
        deduped = deduped[:10]

        result = []
        for h in deduped:
            item = {
                "id": h.id,
                "userId": h.user_id,
                "keyword": h.keyword,
                "operateType": h.operate_type,
                "createTime": h.create_time,
            }
            if h.operate_type == 1:
                parts = h.keyword.split(",")
                if len(parts) >= 2:
                    try:
                        kind = self.db.query(IllnessKind).filter(IllnessKind.id == int(parts[0])).first()
                        item["kind"] = kind.id if kind else "无"
                    except ValueError:
                        item["kind"] = "无"
                    item["nameValue"] = parts[1]
                    kind_name = kind.name if kind else ""
                    item["searchValue"] = kind_name + ("" if parts[1] == "无" else f"|{parts[1]}")
                else:
                    item["kind"] = "无"
                    item["nameValue"] = h.keyword
                    item["searchValue"] = h.keyword
            elif h.operate_type == 2:
                item["nameValue"] = h.keyword
                item["kind"] = "无"
                item["searchValue"] = h.keyword
            elif h.operate_type == 3:
                item["nameValue"] = h.keyword
                item["searchValue"] = h.keyword
                item["kind"] = "无"
            result.append(item)
        return result
