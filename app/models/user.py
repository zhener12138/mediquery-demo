from sqlalchemy import Column, Integer, String, DateTime, func

from app.database import Base


class User(Base):
    __tablename__ = "user"

    id = Column(Integer, primary_key=True, autoincrement=True, comment="用户主键id")
    user_account = Column(String(255), name="user_account", comment="用户账号")
    user_name = Column(String(255), name="user_name", comment="用户的真实名字")
    user_pwd = Column(String(255), name="user_pwd", comment="用户密码")
    user_age = Column(Integer, name="user_age", comment="用户年龄")
    user_sex = Column(String(1), name="user_sex", comment="用户性别")
    user_email = Column(String(255), name="user_email", comment="用户邮箱")
    user_tel = Column(String(50), name="user_tel", comment="手机号")
    role_status = Column(Integer, name="role_status", comment="角色状态，1管理员，0普通用户")
    img_path = Column(String(255), name="img_path", comment="用户头像")
    create_time = Column(DateTime, server_default=func.now(), name="create_time")
    update_time = Column(DateTime, server_default=func.now(), onupdate=func.now(), name="update_time")
