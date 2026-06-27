import time
from datetime import datetime, timedelta
from fastapi import APIRouter, Request, Form, Depends
from sqlalchemy.orm import Session

from app.database import get_db
from app.config import settings
from app.schemas.common import RespResult
from app.services.user_service import UserService
from app.services.email_service import EmailService
from app.models.user import User
from app.utils.helpers import not_empty, is_empty, hash_password, verify_password, create_access_token, build_session_user

router = APIRouter(prefix="/api/login", tags=["auth"])


@router.post("/register")
async def register(
    request: Request,
    userAccount: str = Form(""),
    userPwd: str = Form(""),
    userName: str = Form(""),
    userEmail: str = Form(""),
    code: str = Form(""),
    db: Session = Depends(get_db),
):
    if is_empty(userEmail):
        return RespResult.fail("邮箱不能为空")

    code_key = f"EMAIL_CODE{userEmail}"
    code_data = request.session.get(code_key)
    if not code_data:
        return RespResult.fail("尚未发送验证码")

    if code_data.get("code") != code:
        return RespResult.fail("验证码错误")

    code_time = datetime.fromisoformat(code_data["time"])
    if datetime.now() > code_time + timedelta(minutes=5):
        request.session.pop(code_key, None)
        return RespResult.fail("验证码已经超时")

    user_service = UserService(db)
    existing = user_service.query_by_account(userAccount)
    if not_empty(existing):
        return RespResult.fail("账户已被注册")

    user = User(
        user_account=userAccount,
        user_name=userName,
        user_pwd=hash_password(userPwd),
        user_email=userEmail,
        role_status=0,
        img_path=settings.default_avatar_url,
    )
    user = user_service.save(user)
    request.session["loginUser"] = build_session_user(user)
    token = create_access_token(user.id, user.user_name, user.role_status)
    return RespResult.success("注册成功", data={
        "id": user.id,
        "user_name": user.user_name,
        "user_account": user.user_account,
        "token": token,
    })


@router.post("/login")
async def login(
    request: Request,
    userAccount: str = Form(""),
    userPwd: str = Form(""),
    db: Session = Depends(get_db),
):
    user_service = UserService(db)
    users = user_service.query_by_account(userAccount)
    if is_empty(users):
        return RespResult.fail("账户尚未注册")

    user = users[0]
    if not verify_password(userPwd, user.user_pwd):
        return RespResult.fail("密码错误")

    request.session["loginUser"] = build_session_user(user)
    token = create_access_token(user.id, user.user_name, user.role_status)
    return RespResult.success("登录成功", data={"token": token})


@router.post("/send-email-code")
async def send_email_code(
    request: Request,
    email: str = Form(""),
):
    if is_empty(email):
        return RespResult.fail("邮箱不可为空")

    email_service = EmailService()
    verify_code = email_service.send_email_code(email)

    request.session[f"EMAIL_CODE{email}"] = {
        "email": email,
        "code": verify_code,
        "time": datetime.now().isoformat(),
    }
    return RespResult.success("发送成功")
