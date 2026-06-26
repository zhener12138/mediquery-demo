from fastapi import APIRouter, Form, Depends
from sqlalchemy.orm import Session

from app.database import get_db
from app.schemas.common import RespResult
from app.services.illness_kind_service import IllnessKindService

router = APIRouter(prefix="/api/illness_kind", tags=["illness_kind"])


@router.post("/save")
async def save_illness_kind(
    id: int = Form(None),
    name: str = Form(""),
    info: str = Form(""),
    db: Session = Depends(get_db),
):
    from app.models.illness_kind import IllnessKind

    kind_service = IllnessKindService(db)
    kind = IllnessKind()
    if id is not None:
        kind = kind_service.get(id) or IllnessKind()

    kind.name = name
    kind.info = info

    kind = kind_service.save(kind)
    return RespResult.success("保存成功", data={"id": kind.id})


@router.post("/delete")
async def delete_illness_kind(
    id: int = Form(...),
    db: Session = Depends(get_db),
):
    kind_service = IllnessKindService(db)
    result = kind_service.delete(id)
    if result == 0:
        return RespResult.not_found("数据不存在")
    return RespResult.success("删除成功")
