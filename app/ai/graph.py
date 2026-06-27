import re
from typing import TypedDict, Annotated, List, AsyncGenerator
from langgraph.graph import StateGraph, START, END
from langgraph.graph.message import add_messages
from langgraph.checkpoint.memory import MemorySaver
from langchain_core.messages import BaseMessage

from app.ai.llm import build_llm
from app.ai.prompts import REFUSAL_RESPONSE, FALLBACK_RESPONSE
from app.ai.retriever import retrieve


class DoctorState(TypedDict):
    messages: Annotated[List[BaseMessage], add_messages]
    user_input: str
    is_medical: bool
    response: str


def classify_medical_node(state: DoctorState) -> dict:
    """Guardrail: classify whether the user's question is medical-related."""
    try:
        llm = build_llm(temperature=0.0)
        result = llm.invoke(
            f"判断以下用户问题是否与医疗、健康、疾病、药物、症状相关。只回答一个词：YES 或 NO。\n\n"
            f"用户问题：{state['user_input']}"
        )
        answer = result.content.strip().upper()
        is_medical = "YES" in answer
        return {"is_medical": is_medical}
    except Exception:
        return {"is_medical": True}


def handle_non_medical_node(state: DoctorState) -> dict:
    return {"response": REFUSAL_RESPONSE}


def generate_response_node(state: DoctorState) -> dict:
    """Generate the medical doctor response with RAG context."""
    try:
        llm = build_llm(temperature=0.7)
        user_input = state['user_input']

        # Retrieve relevant knowledge
        retrieved_docs = retrieve(user_input)
        if retrieved_docs:
            context = "\n\n".join(f"- {doc}" for doc in retrieved_docs)
            prompt = (
                f"你是甄同学开发的智能医生。请结合以下医学知识库回答用户问题。\n\n"
                f"相关知识：\n{context}\n\n"
                f"用户问题：{user_input}\n"
                f"请用中文回答，简洁专业。如果知识库中没有相关信息，请基于你的医学知识回答。"
            )
        else:
            prompt = (
                f"你是甄同学开发的智能医生，你只回答与医疗相关的问题。\n"
                f"用户问题：{user_input}\n"
                f"请用中文回答，简洁专业。"
            )

        response = llm.invoke(prompt).content
    except Exception:
        response = FALLBACK_RESPONSE

    return {"response": response}


def route_after_classification(state: DoctorState) -> str:
    if state.get("is_medical", True):
        return "medical"
    return "non_medical"


def build_doctor_graph():
    workflow = StateGraph(DoctorState)

    workflow.add_node("classify_medical", classify_medical_node)
    workflow.add_node("handle_non_medical", handle_non_medical_node)
    workflow.add_node("generate_response", generate_response_node)

    workflow.add_edge(START, "classify_medical")
    workflow.add_conditional_edges(
        "classify_medical",
        route_after_classification,
        {"medical": "generate_response", "non_medical": "handle_non_medical"},
    )
    workflow.add_edge("handle_non_medical", END)
    workflow.add_edge("generate_response", END)

    memory = MemorySaver()
    return workflow.compile(checkpointer=memory)


# Singleton compiled graph
doctor_graph = build_doctor_graph()


async def get_doctor_response(user_input: str, session_id: str) -> str:
    """Entry point called by the chat router."""
    config = {"configurable": {"thread_id": session_id}}
    result = await doctor_graph.ainvoke(
        {"user_input": user_input, "messages": []},
        config=config,
    )
    return result.get("response", FALLBACK_RESPONSE)


async def get_doctor_response_stream(user_input: str, session_id: str) -> AsyncGenerator[str, None]:
    """Streaming entry point — first classify, then stream response token by token."""
    config = {"configurable": {"thread_id": session_id}}

    # Step 1: classify synchronously
    classify_result = await doctor_graph.ainvoke(
        {"user_input": user_input, "messages": []},
        config=config,
    )
    is_medical = classify_result.get("is_medical", True)

    if not is_medical:
        yield REFUSAL_RESPONSE
        return

    # Step 2: stream the medical response with RAG
    llm = build_llm(temperature=0.7)
    retrieved_docs = retrieve(user_input)
    if retrieved_docs:
        context = "\n\n".join(f"- {doc}" for doc in retrieved_docs)
        prompt = (
            f"你是甄同学开发的智能医生。请结合以下医学知识库回答用户问题。\n\n"
            f"相关知识：\n{context}\n\n"
            f"用户问题：{user_input}\n"
            f"请用中文回答，简洁专业。如果知识库中没有相关信息，请基于你的医学知识回答。"
        )
    else:
        prompt = (
            f"你是甄同学开发的智能医生，你只回答与医疗相关的问题。\n"
            f"用户问题：{user_input}\n"
            f"请用中文回答，简洁专业。"
        )
    try:
        async for chunk in llm.astream(prompt):
            if chunk.content:
                yield chunk.content
    except Exception:
        yield FALLBACK_RESPONSE
