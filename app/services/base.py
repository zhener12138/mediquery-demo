from typing import TypeVar, Generic, List, Optional, Any
from sqlalchemy.orm import Session

from app.database import SessionLocal
from app.utils.helpers import not_empty

T = TypeVar("T")


class BaseService(Generic[T]):
    """Generic CRUD service matching the original BaseService.java behavior."""

    model: type = None

    def __init__(self, db: Session = None):
        self.db = db or SessionLocal()

    def save(self, obj: T) -> T:
        if obj.id is not None:
            existing = self.db.query(self.model).filter(self.model.id == obj.id).first()
            if existing:
                for key, val in obj.__dict__.items():
                    if key != "_sa_instance_state" and val is not None:
                        setattr(existing, key, val)
                self.db.commit()
                self.db.refresh(existing)
                return existing
        if obj.id is None or self.get(obj.id) is None:
            self.db.add(obj)
            self.db.commit()
            self.db.refresh(obj)
            return obj
        self.db.commit()
        self.db.refresh(obj)
        return obj

    def get(self, id: Any) -> Optional[T]:
        return self.db.query(self.model).filter(self.model.id == id).first()

    def delete(self, id: Any) -> int:
        result = self.db.query(self.model).filter(self.model.id == id).delete()
        self.db.commit()
        return result

    def query(self, filters: dict = None) -> List[T]:
        q = self.db.query(self.model)
        if filters:
            for key, val in filters.items():
                if not_empty(val):
                    q = q.filter(getattr(self.model, key) == val)
        return q.all()

    def all(self) -> List[T]:
        return self.query()
