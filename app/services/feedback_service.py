from app.models.feedback import Feedback
from app.services.base import BaseService


class FeedbackService(BaseService[Feedback]):
    model = Feedback
