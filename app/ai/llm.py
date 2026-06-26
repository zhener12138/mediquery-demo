from langchain_community.chat_models.tongyi import ChatTongyi
from app.config import settings


def build_llm(temperature: float = 0.7) -> ChatTongyi:
    return ChatTongyi(
        model="qwen-turbo",
        dashscope_api_key=settings.ai_key,
        temperature=temperature,
    )
