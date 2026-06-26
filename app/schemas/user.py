from typing import Optional
from pydantic import BaseModel


class UserLogin(BaseModel):
    user_account: str = ""
    user_pwd: str = ""


class UserRegister(BaseModel):
    user_account: str
    user_pwd: str
    user_name: str
    user_email: str
    user_age: Optional[int] = None
    user_sex: Optional[str] = None
    user_tel: Optional[str] = None


class UserProfileUpdate(BaseModel):
    id: Optional[int] = None
    user_name: Optional[str] = None
    user_age: Optional[int] = None
    user_sex: Optional[str] = None
    user_email: Optional[str] = None
    user_tel: Optional[str] = None
    img_path: Optional[str] = None
    user_account: Optional[str] = None
    user_pwd: Optional[str] = None
    role_status: Optional[int] = None
