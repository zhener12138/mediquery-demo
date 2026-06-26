from fastapi import APIRouter, Request, Form, Depends
from sqlalchemy.orm import Session

from app.database import get_db
from app.schemas.common import RespResult
from app.services.feedback_service import FeedbackService
from app.models.feedback import Feedback

router = APIRouter(prefix="/api/feedback", tags=["feedback"])


@router.post("/save")
async def save_feedback(
    request: Request,
    name: str = Form(""),
    email: str = Form(""),
    title: str = Form(""),
    content: str = Form(""),
    db: Session = Depends(get_db),
):
    feedback_service = FeedbackService(db)
    fb = Feedback(name=name, email=email, title=title, content=content)
    feedback_service.save(fb)
    return RespResult.success("反馈成功")


@router.post("/delete")
async def delete_feedback(
    request: Request,
    id: int = Form(...),
    db: Session = Depends(get_db),
):
    feedback_service = FeedbackService(db)
    result = feedback_service.delete(id)
    if result == 0:
        return RespResult.not_found("数据不存在")
    return RespResult.success("删除成功")
