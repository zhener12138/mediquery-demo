import uuid
import oss2

from app.config import settings


class OssService:
    def __init__(self):
        self.auth = oss2.Auth(settings.oss_access_key, settings.oss_access_secret)
        self.bucket = oss2.Bucket(self.auth, settings.oss_end_point, settings.oss_bucket_name)

    def upload(self, file_data: bytes, path: str, original_filename: str) -> str:
        if not file_data or not path:
            return None
        ext = original_filename[original_filename.rfind("."):] if "." in original_filename else ""
        file_url = f"{path}/{uuid.uuid4().hex}{ext}"
        self.bucket.put_object(file_url, file_data)
        url = f"https://{settings.oss_bucket_name}.{settings.oss_end_point}/{file_url}"
        self.bucket.put_bucket_acl(oss2.BUCKET_ACL_PUBLIC_READ)
        return url
