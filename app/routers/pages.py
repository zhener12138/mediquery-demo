from fastapi import APIRouter, Request, Query, Depends
from fastapi.responses import RedirectResponse
from sqlalchemy.orm import Session

from app.database import get_db
from app.services.illness_service import IllnessService
from app.services.illness_kind_service import IllnessKindService
from app.services.medicine_service import MedicineService
from app.services.history_service import HistoryService
from app.services.illness_medicine_service import IllnessMedicineService
from app.services.feedback_service import FeedbackService
from app.utils.helpers import not_empty

router = APIRouter(tags=["pages"])


def get_login_user(request: Request):
    return request.session.get("loginUser", None)


def get_template_context(request: Request, db: Session, **extra):
    user = get_login_user(request)
    kind_service = IllnessKindService(db)
    kind_list = kind_service.find_list()
    history_list = None
    if user:
        history_service = HistoryService(db)
        history_list = history_service.find_list(user["id"])
    ctx = {
        "request": request,
        "session": request.session,
        "loginUser": user,
        "kindList": kind_list,
        "history": history_list,
    }
    ctx.update(extra)
    return ctx


@router.get("/")
async def index(request: Request, db: Session = Depends(get_db)):
    from fastapi.templating import Jinja2Templates
    from pathlib import Path

    templates_dir = Path(__file__).resolve().parent.parent.parent / "templates"
    templates = Jinja2Templates(directory=str(templates_dir))
    ctx = get_template_context(request, db)
    return templates.TemplateResponse("index.html", ctx)


@router.get("/doctor")
async def doctor(request: Request, db: Session = Depends(get_db)):
    if not get_login_user(request):
        return RedirectResponse(url="/")
    from fastapi.templating import Jinja2Templates
    from pathlib import Path

    templates_dir = Path(__file__).resolve().parent.parent.parent / "templates"
    templates = Jinja2Templates(directory=str(templates_dir))
    ctx = get_template_context(request, db)
    return templates.TemplateResponse("doctor.html", ctx)


@router.get("/logout")
async def logout(request: Request):
    request.session.clear()
    return RedirectResponse(url="/")


@router.get("/profile")
async def profile(request: Request, db: Session = Depends(get_db)):
    if not get_login_user(request):
        return RedirectResponse(url="/")
    from fastapi.templating import Jinja2Templates
    from pathlib import Path

    templates_dir = Path(__file__).resolve().parent.parent.parent / "templates"
    templates = Jinja2Templates(directory=str(templates_dir))
    ctx = get_template_context(request, db)
    return templates.TemplateResponse("profile.html", ctx)


@router.get("/illnesses")
async def find_illness(
    request: Request,
    kind: str = Query(None),
    illnessName: str = Query(None),
    page: int = Query(1),
    db: Session = Depends(get_db),
):
    kind_id = int(kind) if kind else None
    illness_service = IllnessService(db)
    kind_service = IllnessKindService(db)
    history_service = HistoryService(db)

    result = illness_service.find_illness(kind=kind_id, illness_name=illnessName, page=page)

    user = get_login_user(request)
    if kind_id is not None:
        kind_name = kind_service.get(kind_id)
        title = (kind_name.name if kind_name else '') + ('"' + illnessName + '"' + "的搜索结果" if illnessName else "")
    else:
        title = (illnessName is not None and ('"' + illnessName + '"' + "的搜索结果")) or "全部"

    if user is not None and kind_id is not None:
        history_service.insert_one(user["id"], 1, f"{kind_id},{illnessName or '无'}")
    if user is not None and not_empty(illnessName):
        history_service.insert_one(user["id"], 2, illnessName)

    from fastapi.templating import Jinja2Templates
    from pathlib import Path

    templates_dir = Path(__file__).resolve().parent.parent.parent / "templates"
    templates = Jinja2Templates(directory=str(templates_dir))
    ctx = get_template_context(request, db, **result, page=page, kind=kind, illnessName=illnessName, title=title)
    return templates.TemplateResponse("search-illness.html", ctx)


@router.get("/illness/{illness_id}")
async def find_illness_one(
    request: Request,
    illness_id: int,
    db: Session = Depends(get_db),
):
    illness_service = IllnessService(db)
    history_service = HistoryService(db)

    result = illness_service.find_illness_one(illness_id)
    user = get_login_user(request)
    if user and result.get("illness"):
        history_service.insert_one(user["id"], 2, result["illness"].illness_name)

    from fastapi.templating import Jinja2Templates
    from pathlib import Path

    templates_dir = Path(__file__).resolve().parent.parent.parent / "templates"
    templates = Jinja2Templates(directory=str(templates_dir))
    ctx = get_template_context(request, db, **result)
    return templates.TemplateResponse("illness-reviews.html", ctx)


@router.get("/medicines")
async def find_medicines(
    request: Request,
    nameValue: str = Query(None),
    page: int = Query(1),
    db: Session = Depends(get_db),
):
    medicine_service = MedicineService(db)
    history_service = HistoryService(db)

    result = medicine_service.get_medicine_list(name_value=nameValue, page=page)
    user = get_login_user(request)
    if user and not_empty(nameValue):
        history_service.insert_one(user["id"], 3, nameValue)

    from fastapi.templating import Jinja2Templates
    from pathlib import Path

    templates_dir = Path(__file__).resolve().parent.parent.parent / "templates"
    templates = Jinja2Templates(directory=str(templates_dir))
    ctx = get_template_context(request, db, **result, title=nameValue)
    return templates.TemplateResponse("illness.html", ctx)


@router.get("/medicine/{medicine_id}")
async def find_medicine_one(
    request: Request,
    medicine_id: int,
    db: Session = Depends(get_db),
):
    medicine_service = MedicineService(db)
    medicine = medicine_service.get(medicine_id)

    from fastapi.templating import Jinja2Templates
    from pathlib import Path

    templates_dir = Path(__file__).resolve().parent.parent.parent / "templates"
    templates = Jinja2Templates(directory=str(templates_dir))
    ctx = get_template_context(request, db, medicine=medicine)
    return templates.TemplateResponse("medicine.html", ctx)


@router.get("/global-select")
async def global_select(
    request: Request,
    nameValue: str = Query(""),
    db: Session = Depends(get_db),
):
    illness_service = IllnessService(db)
    illness_set = illness_service.global_search(nameValue)

    from fastapi.templating import Jinja2Templates
    from pathlib import Path

    templates_dir = Path(__file__).resolve().parent.parent.parent / "templates"
    templates = Jinja2Templates(directory=str(templates_dir))
    ctx = get_template_context(request, db, illnessSet=illness_set)
    return templates.TemplateResponse("index.html", ctx)


@router.get("/feedback-page")
async def feedback_page(request: Request, db: Session = Depends(get_db)):
    from fastapi.templating import Jinja2Templates
    from pathlib import Path

    templates_dir = Path(__file__).resolve().parent.parent.parent / "templates"
    templates = Jinja2Templates(directory=str(templates_dir))
    ctx = get_template_context(request, db)
    return templates.TemplateResponse("feedback.html", ctx)


# Admin pages
@router.get("/admin/illnesses")
async def admin_all_illness(request: Request, db: Session = Depends(get_db)):
    if not get_login_user(request):
        return RedirectResponse(url="/")
    illness_service = IllnessService(db)
    kind_service = IllnessKindService(db)
    illnesses = illness_service.all()
    for ill in illnesses:
        ill.kind = kind_service.get(ill.kind_id)

    from fastapi.templating import Jinja2Templates
    from pathlib import Path

    templates_dir = Path(__file__).resolve().parent.parent.parent / "templates"
    templates = Jinja2Templates(directory=str(templates_dir))
    ctx = get_template_context(request, db, illnesses=illnesses)
    return templates.TemplateResponse("all-illness.html", ctx)


@router.get("/admin/medicines")
async def admin_all_medical(request: Request, db: Session = Depends(get_db)):
    if not get_login_user(request):
        return RedirectResponse(url="/")
    medicine_service = MedicineService(db)
    medicines = medicine_service.all()

    from fastapi.templating import Jinja2Templates
    from pathlib import Path

    templates_dir = Path(__file__).resolve().parent.parent.parent / "templates"
    templates = Jinja2Templates(directory=str(templates_dir))
    ctx = get_template_context(request, db, medicines=medicines)
    return templates.TemplateResponse("all-medical.html", ctx)


@router.get("/admin/feedbacks")
async def admin_all_feedback(request: Request, db: Session = Depends(get_db)):
    if not get_login_user(request):
        return RedirectResponse(url="/")
    feedback_service = FeedbackService(db)
    feedback_list = feedback_service.all()

    from fastapi.templating import Jinja2Templates
    from pathlib import Path

    templates_dir = Path(__file__).resolve().parent.parent.parent / "templates"
    templates = Jinja2Templates(directory=str(templates_dir))
    ctx = get_template_context(request, db, feedbackList=feedback_list)
    return templates.TemplateResponse("all-feedback.html", ctx)


@router.get("/admin/illnesses/edit")
async def admin_add_illness(
    request: Request,
    id: int = Query(None),
    db: Session = Depends(get_db),
):
    if not get_login_user(request):
        return RedirectResponse(url="/")
    illness_service = IllnessService(db)
    kind_service = IllnessKindService(db)

    illness = illness_service.get(id) if id is not None else None
    kinds = kind_service.all()

    from fastapi.templating import Jinja2Templates
    from pathlib import Path

    templates_dir = Path(__file__).resolve().parent.parent.parent / "templates"
    templates = Jinja2Templates(directory=str(templates_dir))
    ctx = get_template_context(request, db, illness=illness or {}, kinds=kinds)
    return templates.TemplateResponse("add-illness.html", ctx)


@router.get("/admin/medicines/edit")
async def admin_add_medical(
    request: Request,
    id: int = Query(None),
    db: Session = Depends(get_db),
):
    if not get_login_user(request):
        return RedirectResponse(url="/")
    illness_service = IllnessService(db)
    medicine_service = MedicineService(db)
    im_service = IllnessMedicineService(db)

    illnesses = illness_service.all()
    medicine = medicine_service.get(id) if id is not None else None
    if medicine and id:
        for ill in illnesses:
            links = im_service.query({"medicine_id": id, "illness_id": ill.id})
            if links:
                ill.illnessMedicine = links[0]

    from fastapi.templating import Jinja2Templates
    from pathlib import Path

    templates_dir = Path(__file__).resolve().parent.parent.parent / "templates"
    templates = Jinja2Templates(directory=str(templates_dir))
    ctx = get_template_context(request, db, illnesses=illnesses, medicine=medicine or {})
    return templates.TemplateResponse("add-medical.html", ctx)
