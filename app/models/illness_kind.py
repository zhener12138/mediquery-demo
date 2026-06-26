from sqlalchemy import Column, Integer, String, DateTime, func

from app.database import Base


class IllnessKind(Base):
    __tablename__ = "illness_kind"

    id = Column(Integer, primary_key=True, autoincrement=True, comment="主键ID")
    name = Column(String(255), comment="分类名称")
    info = Column(String(255), comment="描述")
    create_time = Column(DateTime, server_default=func.now(), name="create_time")
    update_time = Column(DateTime, server_default=func.now(), onupdate=func.now(), name="update_time")
