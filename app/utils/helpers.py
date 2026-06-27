from datetime import datetime, timedelta
from typing import Optional

from passlib.context import CryptContext
from jose import jwt

from app.config import settings

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")


def hash_password(plain: str) -> str:
    return pwd_context.hash(plain)


def verify_password(plain: str, hashed: str) -> bool:
    return pwd_context.verify(plain, hashed)


def create_access_token(user_id: int, user_name: str, role_status: int) -> str:
    expire = datetime.utcnow() + timedelta(minutes=settings.jwt_expire_minutes)
    payload = {
        "sub": str(user_id),
        "user_name": user_name,
        "role_status": role_status,
        "exp": expire,
    }
    return jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)


def build_session_user(user) -> dict:
    """Build the loginUser session dict without password."""
    return {
        "id": user.id,
        "user_name": user.user_name,
        "user_account": user.user_account,
        "user_email": user.user_email,
        "role_status": user.role_status,
        "img_path": user.img_path,
        "user_age": user.user_age,
        "user_sex": user.user_sex,
        "user_tel": user.user_tel,
    }


def not_empty(val) -> bool:
    """Return True if value is not None and not empty."""
    if val is None:
        return False
    if isinstance(val, str):
        return len(val.strip()) > 0
    if isinstance(val, (list, dict, set, tuple)):
        return len(val) > 0
    return True


def is_empty(val) -> bool:
    return not not_empty(val)
