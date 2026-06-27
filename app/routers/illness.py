from fastapi import APIRouter, Request, Form, Depends
from sqlalchemy.orm import Session

from app.database import get_db
from app.dependencies import get_current_admin
from app.schemas.common import RespResult
from app.services.illness_service import IllnessService
from app.services.illness_kind_service import IllnessKindService
from app.services.history_service import HistoryService
from app.utils.helpers import not_empty, is_empty

router = APIRouter(prefix="/api/illness", tags=["illness"])


@router.post("/save")
async def save_illness(
    request: Request,
    id: int = Form(None),
    kindId: int = Form(None),
    illnessName: str = Form(""),
    includeReason: str = Form(""),
    illnessSymptom: str = Form(""),
    specialSymptom: str = Form(""),
    db: Session = Depends(get_db),
    _admin = Depends(get_current_admin),
):
    from app.models.illness import Illness

    illness_service = IllnessService(db)
    illness = Illness()
    if id is not None:
        illness = illness_service.get(id) or Illness()
        if not illness:
            return RespResult.fail("疾病不存在")

    illness.kind_id = kindId
    illness.illness_name = illnessName
    illness.include_reason = includeReason
    illness.illness_symptom = illnessSymptom
    illness.special_symptom = specialSymptom

    illness = illness_service.save(illness)
    return RespResult.success("保存成功", data={"id": illness.id})


@router.post("/delete")
async def delete_illness(
    request: Request,
    id: int = Form(...),
    db: Session = Depends(get_db),
    _admin = Depends(get_current_admin),
):
    illness_service = IllnessService(db)
    result = illness_service.delete(id)
    if result == 0:
        return RespResult.not_found("数据不存在")
    return RespResult.success("删除成功")
