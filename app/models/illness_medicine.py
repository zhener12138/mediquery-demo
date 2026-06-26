from sqlalchemy import Column, Integer, DateTime, func

from app.database import Base


class IllnessMedicine(Base):
    __tablename__ = "illness_medicine"

    id = Column(Integer, primary_key=True, autoincrement=True, comment="病和药品关联id")
    illness_id = Column(Integer, name="illness_id", comment="病id")
    medicine_id = Column(Integer, name="medicine_id", comment="药品id")
    create_time = Column(DateTime, server_default=func.now(), name="create_time")
    update_time = Column(DateTime, server_default=func.now(), onupdate=func.now(), name="update_time")
