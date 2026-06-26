from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from starlette.middleware.sessions import SessionMiddleware
from pathlib import Path

from app.config import settings

app = FastAPI(title="智慧医药系统")

app.add_middleware(SessionMiddleware, secret_key=settings.secret_key)

# Mount static files
static_dir = Path(__file__).resolve().parent.parent / "static"
static_dir.mkdir(parents=True, exist_ok=True)
app.mount("/static", StaticFiles(directory=str(static_dir)), name="static")

# Import and register routers (done after app creation to avoid circular imports)
from app.routers import auth, pages, user, illness, medicine, illness_medicine, chat, file, feedback, history, illness_kind

app.include_router(auth.router)
app.include_router(pages.router)
app.include_router(user.router)
app.include_router(illness.router)
app.include_router(medicine.router)
app.include_router(illness_medicine.router)
app.include_router(chat.router)
app.include_router(file.router)
app.include_router(feedback.router)
app.include_router(history.router)
app.include_router(illness_kind.router)
