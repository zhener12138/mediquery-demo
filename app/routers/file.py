from fastapi import APIRouter, UploadFile, File, Depends

from app.dependencies import get_current_user
from app.schemas.common import RespResult
from app.services.oss_service import OssService

router = APIRouter(prefix="/api/file", tags=["file"])


@router.post("/upload")
async def upload_file(
    file: UploadFile = File(...),
    _user = Depends(get_current_user),
):
    contents = await file.read()
    oss = OssService()
    url = oss.upload(contents, "upload", file.filename)
    if not url:
        return RespResult.fail("上传失败")
    return RespResult.success("上传成功", data=url)
