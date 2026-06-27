from typing import List

from langchain_community.embeddings import DashScopeEmbeddings
from langchain_community.vectorstores import FAISS
from langchain_core.documents import Document
from sqlalchemy.orm import Session

from app.config import settings
from app.database import SessionLocal
from app.models.illness import Illness
from app.models.illness_kind import IllnessKind
from app.models.medicine import Medicine

_retriever = None


def build_doc_for_illness(illness: Illness) -> str:
    parts = [f"疾病名称：{illness.illness_name}"]
    if illness.kind_id:
        db = SessionLocal()
        try:
            kind = db.query(IllnessKind).filter(IllnessKind.id == illness.kind_id).first()
            if kind:
                parts.append(f"分类：{kind.name}")
        finally:
            db.close()
    if illness.include_reason:
        parts.append(f"诱发因素：{illness.include_reason}")
    if illness.illness_symptom:
        parts.append(f"疾病症状：{illness.illness_symptom}")
    if illness.special_symptom:
        parts.append(f"特殊症状：{illness.special_symptom}")
    return "\n".join(parts)


def build_doc_for_medicine(medicine: Medicine) -> str:
    parts = [f"药品名称：{medicine.medicine_name}"]
    if medicine.medicine_brand:
        parts.append(f"品牌：{medicine.medicine_brand}")
    if medicine.medicine_effect:
        parts.append(f"功效：{medicine.medicine_effect}")
    if medicine.taboo:
        parts.append(f"禁忌：{medicine.taboo}")
    if medicine.us_age:
        parts.append(f"用法用量：{medicine.us_age}")
    if medicine.interaction:
        parts.append(f"药物相互作用：{medicine.interaction}")
    type_map = {0: "西药", 1: "中药", 2: "中成药"}
    if medicine.medicine_type is not None:
        parts.append(f"药品类型：{type_map.get(medicine.medicine_type, '未知')}")
    return "\n".join(parts)


def index_all(db: Session):
    """Build FAISS index from MySQL illness + medicine tables."""
    embeddings = DashScopeEmbeddings(
        model=settings.embedding_model,
        dashscope_api_key=settings.ai_key,
    )

    docs: List[Document] = []

    illnesses = db.query(Illness).all()
    for ill in illnesses:
        text = build_doc_for_illness(ill)
        docs.append(Document(page_content=text, metadata={"type": "illness", "id": ill.id, "name": ill.illness_name}))

    medicines = db.query(Medicine).all()
    for med in medicines:
        text = build_doc_for_medicine(med)
        docs.append(Document(page_content=text, metadata={"type": "medicine", "id": med.id, "name": med.medicine_name}))

    if not docs:
        return

    global _retriever
    _retriever = FAISS.from_documents(docs, embeddings)


def retrieve(query: str, top_k: int = None) -> List[str]:
    """Search FAISS for relevant medical knowledge."""
    if _retriever is None:
        return []
    k = top_k or settings.rag_top_k
    results = _retriever.similarity_search(query, k=k)
    return [doc.page_content for doc in results]
