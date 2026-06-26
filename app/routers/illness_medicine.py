from fastapi import APIRouter, Request, Form, Depends
from sqlalchemy.orm import Session

from app.database import get_db
from app.schemas.common import RespResult
from app.services.illness_medicine_service import IllnessMedicineService
from app.models.illness_medicine import IllnessMedicine

router = APIRouter(prefix="/api/illness_medicine", tags=["illness_medicine"])


@router.post("/save")
async def save_illness_medicine(
    request: Request,
    id: int = Form(None),
    illnessId: int = Form(None),
    medicineId: int = Form(None),
    db: Session = Depends(get_db),
):
    im_service = IllnessMedicineService(db)
    im = IllnessMedicine()
    if id is not None:
        im = im_service.get(id) or IllnessMedicine()

    im.illness_id = illnessId
    im.medicine_id = medicineId

    im = im_service.save(im)
    return RespResult.success("保存成功", data={"id": im.id})


@router.post("/delete")
async def delete_illness_medicine(
    request: Request,
    id: int = Form(...),
    db: Session = Depends(get_db),
):
    im_service = IllnessMedicineService(db)
    result = im_service.delete(id)
    if result == 0:
        return RespResult.not_found("数据不存在")
    return RespResult.success("删除成功")
