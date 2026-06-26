from fastapi import APIRouter, Request, Form, Depends
from sqlalchemy.orm import Session

from app.database import get_db
from app.schemas.common import RespResult
from app.services.user_service import UserService
from app.utils.helpers import not_empty, is_empty

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
):
    user_data = request.session.get("loginUser")
    if not user_data:
        return RespResult.fail("请先登录")

    user_service = UserService(db)
    user = user_service.get(user_data["id"])
    if not user:
        return RespResult.fail("用户不存在")

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
    request.session["loginUser"] = {
        "id": user.id,
        "user_name": user.user_name,
        "user_account": user.user_account,
        "user_pwd": user.user_pwd,
        "user_email": user.user_email,
        "role_status": user.role_status,
        "img_path": user.img_path,
        "user_age": user.user_age,
        "user_sex": user.user_sex,
        "user_tel": user.user_tel,
    }
    return RespResult.success("保存成功")


@router.post("/save-password")
async def save_password(
    request: Request,
    oldPass: str = Form(""),
    newPass: str = Form(""),
    db: Session = Depends(get_db),
):
    user_data = request.session.get("loginUser")
    if not user_data:
        return RespResult.fail("请先登录")

    user_service = UserService(db)
    user = user_service.get(user_data["id"])
    if not user:
        return RespResult.fail("用户不存在")

    if user.user_pwd != oldPass:
        return RespResult.fail("旧密码错误")

    user.user_pwd = newPass
    user = user_service.save(user)
    request.session["loginUser"]["user_pwd"] = user.user_pwd
    return RespResult.success("保存成功")
