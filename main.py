import os
from datetime import datetime, timezone

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import Boolean, DateTime, Float, Integer, String, create_engine, select
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column

DATABASE_URL = os.getenv("DATABASE_URL", "sqlite:///./embrace.db")
API_KEY = os.getenv("API_KEY", "")

engine = create_engine(DATABASE_URL, connect_args={"check_same_thread": False})


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


# ---------- DB 테이블 ----------
class Base(DeclarativeBase):
    pass


class Location(Base):
    __tablename__ = "locations"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    child_id: Mapped[str] = mapped_column(String, index=True)
    latitude: Mapped[float] = mapped_column(Float)
    longitude: Mapped[float] = mapped_column(Float)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)


class Vital(Base):
    __tablename__ = "vitals"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    child_id: Mapped[str] = mapped_column(String, index=True)
    heart_rate: Mapped[float] = mapped_column(Float)
    hrv: Mapped[float | None] = mapped_column(Float, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)


class Command(Base):
    __tablename__ = "commands"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    device_id: Mapped[str] = mapped_column(String, index=True)
    action: Mapped[str] = mapped_column(String)  # "inflate" 또는 "deflate"
    consumed: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)


Base.metadata.create_all(engine)  # 테이블이 없으면 자동 생성


# ---------- 요청 형식 ----------
class LocationIn(BaseModel):
    child_id: str
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)


class VitalIn(BaseModel):
    child_id: str
    heart_rate: float = Field(gt=0, lt=300)
    hrv: float | None = None


class CommandIn(BaseModel):
    device_id: str
    action: str = Field(pattern="^(inflate|deflate)$")


# ---------- 공통 ----------
def check_key(x_api_key: str = Header(default="")):
    if not API_KEY or x_api_key != API_KEY:
        raise HTTPException(status_code=401, detail="invalid api key")


def get_db():
    with Session(engine) as session:
        yield session


def row(obj) -> dict:
    return {c.name: getattr(obj, c.name) for c in obj.__table__.columns}


app = FastAPI(title="EMBRACE API")
auth = [Depends(check_key)]


@app.get("/health")
def health():
    return {"status": "ok"}


# ---------- 위치 ----------
@app.post("/api/location", dependencies=auth)
def post_location(body: LocationIn, db: Session = Depends(get_db)):
    item = Location(**body.model_dump())
    db.add(item)
    db.commit()
    return {"id": item.id}


@app.get("/api/location/latest", dependencies=auth)
def latest_location(child_id: str, db: Session = Depends(get_db)):
    item = db.scalars(
        select(Location).where(Location.child_id == child_id).order_by(Location.id.desc())
    ).first()
    if item is None:
        raise HTTPException(status_code=404, detail="no location yet")
    return row(item)


# ---------- 심박 ----------
@app.post("/api/vitals", dependencies=auth)
def post_vitals(body: VitalIn, db: Session = Depends(get_db)):
    item = Vital(**body.model_dump())
    db.add(item)
    db.commit()
    return {"id": item.id}


@app.get("/api/vitals/latest", dependencies=auth)
def latest_vitals(child_id: str, db: Session = Depends(get_db)):
    item = db.scalars(
        select(Vital).where(Vital.child_id == child_id).order_by(Vital.id.desc())
    ).first()
    if item is None:
        raise HTTPException(status_code=404, detail="no vitals yet")
    return row(item)


# ---------- 조끼 제어 명령 ----------
@app.post("/api/command", dependencies=auth)
def post_command(body: CommandIn, db: Session = Depends(get_db)):
    item = Command(**body.model_dump())
    db.add(item)
    db.commit()
    return {"id": item.id}


@app.get("/api/command/next", dependencies=auth)
def next_command(device_id: str, db: Session = Depends(get_db)):
    """ESP32가 주기적으로 호출. 아직 안 가져간 가장 최신 명령 1개를 주고 나머지는 폐기."""
    pending = db.scalars(
        select(Command)
        .where(Command.device_id == device_id, Command.consumed == False)  # noqa: E712
        .order_by(Command.id.desc())
    ).all()
    if not pending:
        return {"action": "none"}
    for c in pending:
        c.consumed = True
    db.commit()
    return {"action": pending[0].action, "id": pending[0].id}
