from typing import List, Optional
from sqlalchemy.orm import Session
from sqlalchemy import and_

from app.models.user import User
from app.services.base import BaseService


class UserService(BaseService[User]):
    model = User

    def query_by_account(self, user_account: str) -> List[User]:
        return (
            self.db.query(User)
            .filter(User.user_account == user_account)
            .all()
        )
