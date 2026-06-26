from fastapi import Request, HTTPException, Depends
from sqlalchemy.orm import Session

from app.database import get_db


async def get_current_user(request: Request, db: Session = Depends(get_db)):
    from app.models.user import User

    user_data = request.session.get("loginUser")
    if not user_data:
        raise HTTPException(status_code=401, detail="请先登录")
    user = db.query(User).filter(User.id == user_data["id"]).first()
    if not user:
        request.session.clear()
        raise HTTPException(status_code=401, detail="用户不存在")
    return user


async def get_current_admin(request: Request, db: Session = Depends(get_db)):
    user = await get_current_user(request, db)
    if user.role_status != 1:
        raise HTTPException(status_code=403, detail="需要管理员权限")
    return user
