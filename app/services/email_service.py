import random
import smtplib
from email.mime.text import MIMEText

from app.config import settings


class EmailService:
    def send_email_code(self, target_email: str) -> str:
        verify_code = "".join([str(random.randint(0, 9)) for _ in range(6)])
        body = settings.email_template % (verify_code, settings.email_valid_minutes)
        msg = MIMEText(body, "html", "utf-8")
        msg["Subject"] = settings.email_title
        msg["From"] = settings.email_sender
        msg["To"] = target_email

        server = smtplib.SMTP_SSL(settings.email_host, settings.email_port)
        server.login(settings.email_sender, settings.email_password)
        server.sendmail(settings.email_sender, [target_email], msg.as_string())
        server.quit()
        return verify_code

    def send_email(self, target_email: str, title: str, content: str):
        msg = MIMEText(content, "html", "utf-8")
        msg["Subject"] = title
        msg["From"] = settings.email_sender
        msg["To"] = target_email

        server = smtplib.SMTP_SSL(settings.email_host, settings.email_port)
        server.login(settings.email_sender, settings.email_password)
        server.sendmail(settings.email_sender, [target_email], msg.as_string())
        server.quit()
