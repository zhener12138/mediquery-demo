from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # Database
    db_host: str = "127.0.0.1"
    db_port: int = 3306
    db_user: str = "root"
    db_password: str = "123456"
    db_name: str = "smart-medicine"

    # AI
    ai_key: str = ""

    # Email
    email_sender: str = ""
    email_password: str = ""
    email_host: str = "smtp.qq.com"
    email_port: int = 465
    email_valid_minutes: int = 5
    email_title: str = "寻药就医系统 - 用户认证"
    email_template: str = "您的动态验证码为：<strong style='color: red'>%s</strong>，%d分钟内有效，若非本人操作，请勿泄露。"

    # OSS
    oss_bucket_name: str = ""
    oss_end_point: str = "oss-cn-beijing.aliyuncs.com"
    oss_access_key: str = ""
    oss_access_secret: str = ""

    # App
    secret_key: str = "change-me"
    debug: bool = False

    @property
    def db_url(self) -> str:
        return (
            f"mysql+pymysql://{self.db_user}:{self.db_password}"
            f"@{self.db_host}:{self.db_port}/{self.db_name}"

        )

    model_config = {"env_file": ".env", "extra": "ignore"}


settings = Settings()
