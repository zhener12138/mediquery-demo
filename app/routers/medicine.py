from fastapi import APIRouter, Request, Form, Depends
from sqlalchemy.orm import Session

from app.database import get_db
from app.schemas.common import RespResult
from app.services.medicine_service import MedicineService

router = APIRouter(prefix="/api/medicine", tags=["medicine"])


@router.post("/save")
async def save_medicine(
    request: Request,
    id: int = Form(None),
    medicineName: str = Form(""),
    keyword: str = Form(""),
    medicineEffect: str = Form(""),
    medicineBrand: str = Form(""),
    interaction: str = Form(""),
    taboo: str = Form(""),
    usAge: str = Form(""),
    medicineType: int = Form(None),
    imgPath: str = Form(""),
    medicinePrice: float = Form(None),
    db: Session = Depends(get_db),
):
    from app.models.medicine import Medicine

    medicine_service = MedicineService(db)
    medicine = Medicine()
    if id is not None:
        medicine = medicine_service.get(id) or Medicine()
        if not medicine:
            return RespResult.fail("药品不存在")

    medicine.medicine_name = medicineName
    medicine.keyword = keyword
    medicine.medicine_effect = medicineEffect
    medicine.medicine_brand = medicineBrand
    medicine.interaction = interaction or None
    medicine.taboo = taboo or None
    medicine.us_age = usAge
    medicine.medicine_type = medicineType
    medicine.img_path = imgPath or None
    medicine.medicine_price = medicinePrice

    medicine = medicine_service.save(medicine)
    return RespResult.success("保存成功", data={"id": medicine.id})


@router.post("/delete")
async def delete_medicine(
    request: Request,
    id: int = Form(...),
    db: Session = Depends(get_db),
):
    medicine_service = MedicineService(db)
    result = medicine_service.delete(id)
    if result == 0:
        return RespResult.not_found("数据不存在")
    return RespResult.success("删除成功")
