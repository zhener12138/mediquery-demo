from fastapi import APIRouter, Request, Form, Depends
from sqlalchemy.orm import Session

from app.database import get_db
from app.dependencies import get_current_user
from app.schemas.common import RespResult
from app.services.user_service import UserService
from app.utils.helpers import not_empty, is_empty, verify_password, hash_password, build_session_user

router = APIRouter(prefix="/api/user", tags=["user"])


@router.post("/save-profile")
async def save_profile(
    request: Request,
    userAccount: str = Form(""),
    userName: str = Form(""),
    userAge: int = Form(None),
    userSex: str = Form(""),
    userEmail: str = Form(""),
    userTel: str = Form(""),
    db: Session = Depends(get_db),
    current_user = Depends(get_current_user),
):
    user_service = UserService(db)
    user = current_user

    if not_empty(userAccount):
        user.user_account = userAccount
    if not_empty(userName):
        user.user_name = userName
    if userAge is not None:
        user.user_age = userAge
    if not_empty(userSex):
        user.user_sex = userSex
    if not_empty(userEmail):
        user.user_email = userEmail
    if not_empty(userTel):
        user.user_tel = userTel

    user = user_service.save(user)
    request.session["loginUser"] = build_session_user(user)
    return RespResult.success("保存成功")


@router.post("/save-password")
async def save_password(
    request: Request,
    oldPass: str = Form(""),
    newPass: str = Form(""),
    db: Session = Depends(get_db),
    current_user = Depends(get_current_user),
):
    user_service = UserService(db)
    user = current_user

    if not verify_password(oldPass, user.user_pwd):
        return RespResult.fail("旧密码错误")

    user.user_pwd = hash_password(newPass)
    user_service.save(user)
    return RespResult.success("保存成功")
