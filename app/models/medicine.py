from sqlalchemy import Column, Integer, String, DateTime, Text, Numeric, func

from app.database import Base


class Medicine(Base):
    __tablename__ = "medicine"

    id = Column(Integer, primary_key=True, autoincrement=True, comment="药品主键ID")
    medicine_name = Column(String(100), name="medicine_name", comment="药的名字")
    keyword = Column(String(255), comment="关键字搜索")
    medicine_effect = Column(Text, name="medicine_effect", comment="药的功效")
    medicine_brand = Column(String(255), name="medicine_brand", comment="药的品牌")
    interaction = Column(Text, comment="药的相互作用")
    taboo = Column(Text, comment="禁忌")
    us_age = Column(Text, name="us_age", comment="用法用量")
    medicine_type = Column(Integer, name="medicine_type", comment="药的类型，0西药，1中药，2中成药")
    img_path = Column(String(255), name="img_path", comment="相关图片路径")
    medicine_price = Column(Numeric(10, 2), name="medicine_price", comment="药的价格")
    create_time = Column(DateTime, server_default=func.now(), name="create_time")
    update_time = Column(DateTime, server_default=func.now(), onupdate=func.now(), name="update_time")
