from fastapi import APIRouter, Request, Form, Depends
from sqlalchemy.orm import Session

from app.database import get_db
from app.dependencies import get_current_admin
from app.schemas.common import RespResult
from app.services.history_service import HistoryService

router = APIRouter(prefix="/api/history", tags=["history"])


@router.post("/save")
async def save_history(
    request: Request,
    id: int = Form(None),
    userId: int = Form(None),
    keyword: str = Form(""),
    operateType: int = Form(None),
    db: Session = Depends(get_db),
    _admin = Depends(get_current_admin),
):
    from app.models.history import History

    history_service = HistoryService(db)
    h = History()
    if id is not None:
        h = history_service.get(id) or History()

    h.user_id = userId
    h.keyword = keyword
    h.operate_type = operateType

    h = history_service.save(h)
    return RespResult.success("保存成功", data={"id": h.id})


@router.post("/delete")
async def delete_history(
    request: Request,
    id: int = Form(...),
    db: Session = Depends(get_db),
    _admin = Depends(get_current_admin),
):
    history_service = HistoryService(db)
    result = history_service.delete(id)
    if result == 0:
        return RespResult.not_found("数据不存在")
    return RespResult.success("删除成功")
