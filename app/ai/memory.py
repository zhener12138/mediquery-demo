from langchain_community.chat_message_histories import ChatMessageHistory
from langchain_core.chat_history import BaseChatMessageHistory
from typing import Dict

store: Dict[str, ChatMessageHistory] = {}

MAX_HISTORY_MESSAGES = 10


def get_session_history(session_id: str) -> BaseChatMessageHistory:
    if session_id not in store:
        store[session_id] = ChatMessageHistory()
    return store[session_id]


def trim_history(history: ChatMessageHistory):
    """Keep only the last MAX_HISTORY_MESSAGES."""
    if len(history.messages) > MAX_HISTORY_MESSAGES:
        history.messages = history.messages[-MAX_HISTORY_MESSAGES:]
