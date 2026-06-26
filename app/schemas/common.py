from typing import Optional, Any
from pydantic import BaseModel


class RespResult(BaseModel):
    code: str = "SUCCESS"
    message: str = "请求成功"
    data: Optional[Any] = None

    @staticmethod
    def success(message: str = "请求成功", data: Any = None) -> dict:
        return {"code": "SUCCESS", "message": message, "data": data}

    @staticmethod
    def fail(message: str = "请求失败", data: Any = None) -> dict:
        return {"code": "FAIL", "message": message, "data": data}

    @staticmethod
    def not_found(message: str = "未查询到数据", data: Any = None) -> dict:
        return {"code": "NOT_FOUND", "message": message, "data": data}
