from sqlalchemy import Column, Integer, DateTime, func

from app.database import Base


class Pageview(Base):
    __tablename__ = "pageview"

    id = Column(Integer, primary_key=True, autoincrement=True, comment="主键id")
    pageviews = Column(Integer, comment="浏览量")
    illness_id = Column(Integer, name="illness_id", comment="病的id")
