from sqlalchemy import Column, Integer, String, DateTime, Text, func

from app.database import Base


class Illness(Base):
    __tablename__ = "illness"

    id = Column(Integer, primary_key=True, autoincrement=True, comment="疾病id")
    kind_id = Column(Integer, name="kind_id", comment="疾病分类ID")
    illness_name = Column(String(100), name="illness_name", comment="疾病名字")
    include_reason = Column(Text, name="include_reason", comment="诱发因素")
    illness_symptom = Column(Text, name="illness_symptom", comment="疾病症状")
    special_symptom = Column(Text, name="special_symptom", comment="特殊症状")
    create_time = Column(DateTime, server_default=func.now(), name="create_time")
    update_time = Column(DateTime, server_default=func.now(), onupdate=func.now(), name="update_time")
