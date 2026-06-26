from sqlalchemy import Column, Integer, String, DateTime, func

from app.database import Base


class History(Base):
    __tablename__ = "history"

    id = Column(Integer, primary_key=True, autoincrement=True, comment="用户搜索历史主键id")
    user_id = Column(Integer, name="user_id", comment="用户ID")
    keyword = Column(String(255), comment="搜索关键字")
    operate_type = Column(Integer, name="operate_type", comment="类型：1搜索，2科目，3药品")
    create_time = Column(DateTime, server_default=func.now(), name="create_time")
    update_time = Column(DateTime, server_default=func.now(), onupdate=func.now(), name="update_time")
