from sqlalchemy import Column, Integer, String, DateTime, Text, func

from app.database import Base


class Feedback(Base):
    __tablename__ = "feedback"

    id = Column(Integer, primary_key=True, autoincrement=True, comment="主键ID")
    name = Column(String(11), comment="反馈用户")
    email = Column(String(255), comment="邮箱地址")
    title = Column(String(255), comment="反馈标题")
    content = Column(Text, comment="反馈内容")
    create_time = Column(DateTime, server_default=func.now(), name="create_time")
    update_time = Column(DateTime, server_default=func.now(), onupdate=func.now(), name="update_time")
