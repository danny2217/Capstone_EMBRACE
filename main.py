"""EMBRACE 백엔드 (HTTP).

구조: [워치] -> [아동용 폰 앱] -> (HTTP) -> 이 서버 -> (HTTP) -> [보호자 앱]
- 조끼(ESP32)는 Wi-Fi 가 없어서 서버에 직접 붙지 않는다. 서버에 쓰는 쪽은 아동용 폰 앱뿐이다.
- 조끼 상태 필드는 BLE STATUS 20바이트(firmware hr_link.cpp)를 폰 앱이 풀어 놓은 값 그대로다.
- 원격 명령은 배기(exhaust)만 받는다. 가압은 조끼 앞에서만 가능하다 (BLE·서버 모두 인증이 약하므로).
규격 설명: API.md
"""
import os
from datetime import datetime, timedelta, timezone

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from pydantic import BaseModel, Field
from sqlalchemy import BigInteger, Boolean, DateTime, Float, Integer, String, create_engine, select
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column

DATABASE_URL = os.getenv("DATABASE_URL", "sqlite:///./embrace.db")
API_KEY = os.getenv("API_KEY", "")
COMMAND_TTL_SEC = 60  # 이보다 오래된 원격 배기 명령은 폰에 전달하지 않는다

engine = create_engine(DATABASE_URL, connect_args={"check_same_thread": False})

VEST_STATE_NAMES = {
    0: "시작 배기", 1: "대기", 2: "가압 중", 3: "유지 중",
    4: "배기 중", 5: "휴식", 6: "비상 배기", 7: "잠금",
}
HR_STATE_NAMES = {0: "없음", 1: "끊김", 2: "재합류", 3: "정상"}
# 앱 SOS 와 같은 값. 조끼는 'd'(배기)/'x'(비상 배기)만 받는다.
EXHAUST_BLE_BYTE = "x"


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def as_utc(dt: datetime) -> datetime:
    # SQLite 는 시간대 정보를 버리므로 저장된 값은 UTC 로 간주한다
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


# ---------- DB 테이블 ----------
# 예전 테이블(locations, vitals, commands, sensor_readings)과 이름을 다르게 해서
# 기존 embrace.db 가 있어도 충돌하지 않게 했다. 예전 테이블은 더 이상 쓰지 않는다.
class Base(DeclarativeBase):
    pass


class VestStatus(Base):
    __tablename__ = "vest_status"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    child_id: Mapped[str] = mapped_column(String, index=True)
    seq: Mapped[int] = mapped_column(Integer)
    state: Mapped[int] = mapped_column(Integer)
    pump: Mapped[bool] = mapped_column(Boolean)
    valve_open: Mapped[bool] = mapped_column(Boolean)
    armed: Mapped[bool] = mapped_column(Boolean)
    overload: Mapped[bool] = mapped_column(Boolean)
    breath_valid: Mapped[bool] = mapped_column(Boolean)
    breath_healthy: Mapped[bool] = mapped_column(Boolean)
    apnea: Mapped[bool] = mapped_column(Boolean)
    frames_ok: Mapped[bool] = mapped_column(Boolean)
    score: Mapped[int] = mapped_column(Integer)
    hr_state: Mapped[int] = mapped_column(Integer)
    bpm: Mapped[int] = mapped_column(Integer)
    breath_rate: Mapped[float] = mapped_column(Float)
    ie_ratio: Mapped[float] = mapped_column(Float)
    grip_l: Mapped[int] = mapped_column(Integer)
    grip_r: Mapped[int] = mapped_column(Integer)
    comp_max_mv: Mapped[int] = mapped_column(Integer)
    in_state_sec: Mapped[int] = mapped_column(Integer)
    cycles: Mapped[int] = mapped_column(Integer)
    ts: Mapped[int | None] = mapped_column(BigInteger, nullable=True)  # 폰 기준 epoch ms
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc, index=True)


class HeartRate(Base):
    __tablename__ = "heart_rates"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    child_id: Mapped[str] = mapped_column(String, index=True)
    bpm: Mapped[int] = mapped_column(Integer)
    valid: Mapped[bool] = mapped_column(Boolean)
    source: Mapped[str] = mapped_column(String)  # "watch" 또는 "vest"
    ts: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc, index=True)


class ChildLocation(Base):
    __tablename__ = "child_locations"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    child_id: Mapped[str] = mapped_column(String, index=True)
    lat: Mapped[float] = mapped_column(Float)
    lon: Mapped[float] = mapped_column(Float)
    acc_m: Mapped[float | None] = mapped_column(Float, nullable=True)
    ts: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc, index=True)


class Alert(Base):
    __tablename__ = "alerts"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    child_id: Mapped[str] = mapped_column(String, index=True)
    level: Mapped[str] = mapped_column(String)  # danger / warning / stable
    code: Mapped[str] = mapped_column(String)   # apnea, overload, inflate, emergency, lockout, ble_lost, sos
    title: Mapped[str] = mapped_column(String)
    message: Mapped[str] = mapped_column(String)
    ts: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc, index=True)


class VestCommand(Base):
    __tablename__ = "vest_commands"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    child_id: Mapped[str] = mapped_column(String, index=True)
    action: Mapped[str] = mapped_column(String)  # "exhaust" 만
    consumed: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)


Base.metadata.create_all(engine)  # 테이블이 없으면 자동 생성


# ---------- 요청 형식 ----------
ChildId = Field(min_length=1, max_length=128)


class VestStatusIn(BaseModel):
    child_id: str = ChildId
    seq: int = Field(ge=0, le=255)
    state: int = Field(ge=0, le=7)
    pump: bool
    valve_open: bool
    armed: bool
    overload: bool
    breath_valid: bool
    breath_healthy: bool
    apnea: bool
    frames_ok: bool
    score: int = Field(ge=0, le=255)
    hr_state: int = Field(ge=0, le=3)
    bpm: int = Field(ge=0, le=255)
    breath_rate: float = Field(ge=0, le=6553.5)  # 회/분 (STATUS 값 ÷10)
    ie_ratio: float = Field(ge=0, le=655.35)     # 들숨÷날숨 (STATUS 값 ÷100)
    grip_l: int = Field(ge=0, le=65535)
    grip_r: int = Field(ge=0, le=65535)
    comp_max_mv: int = Field(ge=0, le=65535)
    in_state_sec: int = Field(ge=0, le=65535)
    cycles: int = Field(ge=0, le=255)
    ts: int | None = None


class HeartRateIn(BaseModel):
    child_id: str = ChildId
    bpm: int = Field(ge=0, le=255)
    valid: bool = True
    source: str = Field(default="watch", pattern="^(watch|vest)$")
    ts: int | None = None


class LocationIn(BaseModel):
    child_id: str = ChildId
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)
    acc_m: float | None = Field(default=None, ge=0)
    ts: int | None = None


class AlertIn(BaseModel):
    child_id: str = ChildId
    level: str = Field(pattern="^(danger|warning|stable)$")
    code: str = Field(min_length=1, max_length=32)
    title: str = Field(min_length=1, max_length=100)
    message: str = Field(max_length=500)
    ts: int | None = None


class CommandIn(BaseModel):
    child_id: str = ChildId
    action: str = Field(pattern="^exhaust$")  # 가압은 받지 않는다


# ---------- 공통 ----------
def check_key(x_api_key: str = Header(default="")):
    if not API_KEY or x_api_key != API_KEY:
        raise HTTPException(status_code=401, detail="invalid api key")


def get_db():
    with Session(engine) as session:
        yield session


def row(obj) -> dict:
    out = {c.name: getattr(obj, c.name) for c in obj.__table__.columns}
    created = as_utc(obj.created_at)
    out["created_at"] = created.isoformat().replace("+00:00", "Z")
    out["age_sec"] = round((now_utc() - created).total_seconds(), 1)
    return out


def status_row(obj: VestStatus) -> dict:
    out = row(obj)
    out["state_name"] = VEST_STATE_NAMES.get(obj.state, "알 수 없음")
    out["hr_state_name"] = HR_STATE_NAMES.get(obj.hr_state, "알 수 없음")
    # 화면 표시용 "1 : x" (x = 날숨÷들숨). 조끼가 보내는 ie_ratio 는 들숨÷날숨이다.
    out["ie_text"] = (
        f"1 : {1 / obj.ie_ratio:.1f}" if obj.breath_valid and obj.ie_ratio > 0 else None
    )
    return out


def latest(db: Session, model, child_id: str):
    return db.scalars(
        select(model).where(model.child_id == child_id).order_by(model.id.desc())
    ).first()


def add(db: Session, item) -> dict:
    db.add(item)
    db.commit()
    return {"id": item.id}


app = FastAPI(title="EMBRACE API", version="2.0")
auth = [Depends(check_key)]


@app.get("/health")
def health():
    return {"status": "ok"}


# ---------- 조끼 상태 (아동용 폰 -> 서버) ----------
@app.post("/api/vest/status", dependencies=auth)
def post_vest_status(body: VestStatusIn, db: Session = Depends(get_db)):
    return add(db, VestStatus(**body.model_dump()))


@app.get("/api/vest/status/latest", dependencies=auth)
def latest_vest_status(child_id: str, db: Session = Depends(get_db)):
    item = latest(db, VestStatus, child_id)
    if item is None:
        raise HTTPException(status_code=404, detail="no vest status yet")
    return status_row(item)


@app.get("/api/vest/status/history", dependencies=auth)
def vest_status_history(
    child_id: str,
    minutes: int = Query(default=10, ge=1, le=1440),
    limit: int = Query(default=600, ge=1, le=5000),
    db: Session = Depends(get_db),
):
    since = (now_utc() - timedelta(minutes=minutes)).replace(tzinfo=None)
    items = db.scalars(
        select(VestStatus)
        .where(VestStatus.child_id == child_id, VestStatus.created_at >= since)
        .order_by(VestStatus.id.desc())
        .limit(limit)
    ).all()
    return [status_row(i) for i in reversed(items)]


# ---------- 심박 ----------
@app.post("/api/heart", dependencies=auth)
def post_heart(body: HeartRateIn, db: Session = Depends(get_db)):
    return add(db, HeartRate(**body.model_dump()))


@app.get("/api/heart/latest", dependencies=auth)
def latest_heart(child_id: str, db: Session = Depends(get_db)):
    item = latest(db, HeartRate, child_id)
    if item is None:
        raise HTTPException(status_code=404, detail="no heart rate yet")
    return row(item)


# ---------- 위치 ----------
@app.post("/api/location", dependencies=auth)
def post_location(body: LocationIn, db: Session = Depends(get_db)):
    return add(db, ChildLocation(**body.model_dump()))


@app.get("/api/location/latest", dependencies=auth)
def latest_location(child_id: str, db: Session = Depends(get_db)):
    item = latest(db, ChildLocation, child_id)
    if item is None:
        raise HTTPException(status_code=404, detail="no location yet")
    return row(item)


# ---------- 알림 ----------
@app.post("/api/alerts", dependencies=auth)
def post_alert(body: AlertIn, db: Session = Depends(get_db)):
    return add(db, Alert(**body.model_dump()))


@app.get("/api/alerts", dependencies=auth)
def list_alerts(
    child_id: str,
    after_id: int = Query(default=0, ge=0),
    limit: int = Query(default=50, ge=1, le=200),
    db: Session = Depends(get_db),
):
    """보호자 앱이 마지막으로 받은 id 를 after_id 로 넘기면 그 뒤의 새 알림만 준다."""
    items = db.scalars(
        select(Alert)
        .where(Alert.child_id == child_id, Alert.id > after_id)
        .order_by(Alert.id.asc())
        .limit(limit)
    ).all()
    return [row(i) for i in items]


# ---------- 보호자 화면용 한 번에 받기 ----------
@app.get("/api/summary", dependencies=auth)
def summary(child_id: str, db: Session = Depends(get_db)):
    vest = latest(db, VestStatus, child_id)
    heart = latest(db, HeartRate, child_id)
    loc = latest(db, ChildLocation, child_id)
    alert = latest(db, Alert, child_id)
    return {
        "child_id": child_id,
        "vest": status_row(vest) if vest else None,
        "heart": row(heart) if heart else None,
        "location": row(loc) if loc else None,
        "last_alert_id": alert.id if alert else 0,
    }


# ---------- 원격 배기 명령 (보호자 -> 서버 -> 아동용 폰 -> BLE CMD -> 조끼) ----------
@app.post("/api/command", dependencies=auth)
def post_command(body: CommandIn, db: Session = Depends(get_db)):
    return add(db, VestCommand(**body.model_dump()))


@app.get("/api/command/next", dependencies=auth)
def next_command(child_id: str, db: Session = Depends(get_db)):
    """아동용 폰 앱이 주기적으로 호출. 아직 안 가져간 최신 명령 1개를 주고 나머지는 폐기.
    COMMAND_TTL_SEC 보다 오래된 명령은 전달하지 않는다 (늦게 켜진 폰이 옛 명령을 실행하지 않게)."""
    pending = db.scalars(
        select(VestCommand)
        .where(VestCommand.child_id == child_id, VestCommand.consumed == False)  # noqa: E712
        .order_by(VestCommand.id.desc())
    ).all()
    if not pending:
        return {"action": "none"}
    for c in pending:
        c.consumed = True
    db.commit()
    newest = pending[0]
    if now_utc() - as_utc(newest.created_at) > timedelta(seconds=COMMAND_TTL_SEC):
        return {"action": "none", "expired_id": newest.id}
    return {"action": newest.action, "ble_byte": EXHAUST_BLE_BYTE, "id": newest.id}
