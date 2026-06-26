from fastapi import APIRouter, Request, Form, Depends
from fastapi.responses import StreamingResponse
from sqlalchemy.orm import Session

from app.database import get_db
from app.schemas.common import RespResult
from app.ai.graph import get_doctor_response, get_doctor_response_stream

router = APIRouter(prefix="/api/chat", tags=["chat"])


@router.post("/query")
async def chat_query(
    request: Request,
    content: str = Form(""),
    db: Session = Depends(get_db),
):
    session_id = request.session.get("session_id", None)
    if not session_id:
        import uuid
        session_id = str(uuid.uuid4())
        request.session["session_id"] = session_id

    response_text = await get_doctor_response(content, session_id)
    return RespResult.success(response_text)


@router.post("/stream")
async def chat_query_stream(
    request: Request,
    content: str = Form(""),
    db: Session = Depends(get_db),
):
    session_id = request.session.get("session_id", None)
    if not session_id:
        import uuid
        session_id = str(uuid.uuid4())
        request.session["session_id"] = session_id

    async def event_stream():
        async for token in get_doctor_response_stream(content, session_id):
            yield f"data: {token}\n\n"
        yield "data: [DONE]\n\n"

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )
