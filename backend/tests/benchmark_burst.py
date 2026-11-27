"""Reproducible 70-student post-game burst benchmark.

Measures:
  1. Concurrent FaceMatchingEngine.extract_capture throughput (pool + lock-free ORT)
     versus strict serialized locking, plus SessionFaceRegistry buffer append speed.
  2. End-to-end 70-client concurrent asyncio.gather burst against /submit-neighbors,
     capturing wall time, p50/p95 latency, and max concurrent DB transactions.

Run from backend/:
  PYTHONPATH=. ./.venv/bin/python tests/benchmark_burst.py
"""

from __future__ import annotations

import asyncio
import io
import os
import statistics
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

import cv2
import numpy as np
from httpx import ASGITransport, AsyncClient
from PIL import Image
from sqlalchemy import event
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.config import settings
from app.core.security import create_access_token
from app.database import get_db
from app.main import app
from app.models.attendance import AttendanceSession, Base, Course, FaceEnrollment, User
from app.services.face_engine import SessionFaceRegistry, face_registry
from app.services.rate_limiter import submit_rate_limiter


def _build_synthetic_face_like_jpeg() -> bytes:
    rng = np.random.default_rng(123)
    img = rng.integers(60, 200, size=(480, 640, 3), dtype=np.uint8)
    ok, buf = cv2.imencode(".jpg", img)
    assert ok
    return buf.tobytes()


def bench_face_stage(n: int = 70) -> None:
    eng = face_registry.engine
    raw = _build_synthetic_face_like_jpeg()
    # Warm-up
    eng.extract_capture(raw)

    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=min(8, max(2, os.cpu_count() or 2))) as ex:
        list(ex.map(eng.extract_capture, [raw] * n))
    t_par = (time.perf_counter() - t0) * 1000.0

    # Serial baseline for comparison
    t0 = time.perf_counter()
    for _ in range(n):
        with eng._lock:
            eng.extract_capture(raw)
    t_ser = (time.perf_counter() - t0) * 1000.0

    # Time real YuNet detect + ArcFace ORT inference concurrency (70x)
    crop = np.zeros((112, 112, 3), dtype=np.uint8)
    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=min(8, max(2, os.cpu_count() or 2))) as ex:
        list(ex.map(eng._arcface_feature, [crop] * n))
    t_arc_par = (time.perf_counter() - t0) * 1000.0

    t0 = time.perf_counter()
    for _ in range(n):
        eng._arcface_feature(crop)
    t_arc_ser = (time.perf_counter() - t0) * 1000.0

    # Compare preallocated buffer vs legacy vstack over 70 students
    rng = np.random.default_rng(7)
    dim = eng.embed_dim
    vecs = [rng.standard_normal(dim).astype(np.float32) for _ in range(n)]
    reg = SessionFaceRegistry(engine=eng)
    t0 = time.perf_counter()
    for i, v in enumerate(vecs):
        reg.register_and_check("bench", f"S{i}", f"R{i}", embedding=v.reshape(1, dim))
    t_buf = (time.perf_counter() - t0) * 1000.0

    t0 = time.perf_counter()
    mat = None
    for v in vecs:
        row = v.reshape(1, dim)
        mat = row if mat is None else np.vstack([mat, row])
    t_vstack = (time.perf_counter() - t0) * 1000.0

    print(
        f"[Face YuNet N={n}] pooled={t_par:.1f}ms vs serialized={t_ser:.1f}ms "
        f"(speedup={t_ser / max(t_par, 1e-3):.2f}x)"
    )
    print(
        f"[ArcFace ORT N={n}] concurrent={t_arc_par:.1f}ms vs serial={t_arc_ser:.1f}ms "
        f"(speedup={t_arc_ser / max(t_arc_par, 1e-3):.2f}x) | "
        f"matrix_buf={t_buf:.2f}ms vs vstack={t_vstack:.2f}ms"
    )


async def bench_http_70_burst(tmp_db_path: str = "/tmp/rotom_burst_bench.sqlite3") -> None:
    if os.path.exists(tmp_db_path):
        os.remove(tmp_db_path)

    face_registry.clear_all()
    submit_rate_limiter._events.clear()
    engine = create_async_engine(
        f"sqlite+aiosqlite:///{tmp_db_path}",
        echo=False,
        connect_args={"check_same_thread": False},
    )
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)

    active_txns = 0
    max_active_txns = 0

    @event.listens_for(engine.sync_engine, "begin")
    def _on_begin(conn):
        nonlocal active_txns, max_active_txns
        active_txns += 1
        if active_txns > max_active_txns:
            max_active_txns = active_txns

    @event.listens_for(engine.sync_engine, "commit")
    def _on_commit(conn):
        nonlocal active_txns
        active_txns = max(0, active_txns - 1)

    @event.listens_for(engine.sync_engine, "rollback")
    def _on_rollback(conn):
        nonlocal active_txns
        active_txns = max(0, active_txns - 1)

    async def override_get_db():
        async with factory() as s:
            yield s

    app.dependency_overrides[get_db] = override_get_db

    # Create 1x1x... synthetic JPEG matching test env synthetic fallback or real JPEG
    img = Image.new("RGB", (96, 96), color=(160, 140, 120))
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    raw_jpeg = buf.getvalue()

    try:
        async with factory() as s:
            teacher = User(
                id=uuid.uuid4(), full_name="Prof Burst", email="burst.t@univ.edu",
                hashed_password="x", role="TEACHER", roll_number="EMP900",
            )
            course = Course(
                id=uuid.uuid4(), course_code="BRST70", course_name="Burst Load",
                teacher_id=teacher.id, program="B.Tech", semester=3,
            )
            from datetime import datetime, timedelta, timezone
            now = datetime.now(timezone.utc)
            sess = AttendanceSession(
                id=uuid.uuid4(), course_id=course.id, teacher_id=teacher.id,
                session_token="BR70", session_salt="deadbeef" * 4, status="ACTIVE",
                eligibility_program="B.Tech", eligibility_semester=3,
                total_enrolled=70, started_at=now,
                game_starts_at=now + timedelta(seconds=10),
                game_ends_at=now + timedelta(seconds=38),
            )
            s.add_all([teacher, course, sess])
            students = []
            for i in range(70):
                u = User(
                    id=uuid.uuid4(), full_name=f"S{i}", email=f"burst.s{i}@univ.edu",
                    hashed_password="x", role="STUDENT", roll_number=f"22BR{i:03d}",
                    program="B.Tech", semester=3,
                )
                students.append(u)
            s.add_all(students)

            # Pre-seed flipped 1:1 enrollments so all 70 concurrent requests execute
            # full DB read + release + face capture + 1:1 check + AttendanceRecord write.
            os.environ["PYTEST_CURRENT_TEST"] = "benchmark_burst"
            vec = face_registry.engine.extract_capture(raw_jpeg).embedding.flatten().tolist()
            refs = [
                {
                    "vec": list(vec), "day": "2026-01-01", "quality": 100.0,
                    "yaw": 0.0, "exposure": 120.0, "created_at": "2026-01-01T00:00:00+00:00",
                }
                for _ in range(5)
            ]
            for u in students:
                s.add(FaceEnrollment(
                    student_id=u.id, embeddings=refs, centroid=list(vec),
                    ref_count=5, ref_days=2, distinct_days=["2026-01-01", "2026-01-02"],
                ))
            await s.commit()
            from app.routers.attendance import valid_wire_salts
            wire_salt = valid_wire_salts(sess)[0]
            sid = str(sess.id)
            tokens = [create_access_token({"sub": str(u.id)}) for u in students]

        prev_pytest_env = os.environ.get("PYTEST_CURRENT_TEST")
        os.environ["PYTEST_CURRENT_TEST"] = "benchmark_burst"
        latencies_ms: list[float] = []
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            async def one_student(i: int):
                t_start = time.perf_counter()
                res = await client.post(
                    f"/api/v1/attendance/sessions/{sid}/submit-neighbors",
                    content=raw_jpeg,
                    headers={
                        "Content-Type": "image/jpeg",
                        "Authorization": f"Bearer {tokens[i]}",
                        "X-Session-Salt": wire_salt,
                        "X-Neighbors": "PEER01,PEER02,PEER03",
                        "X-Game-Score": "20",
                    },
                )
                latencies_ms.append((time.perf_counter() - t_start) * 1000.0)
                return res.status_code

            t_all = time.perf_counter()
            codes = await asyncio.gather(*(one_student(i) for i in range(70)))
            wall_ms = (time.perf_counter() - t_all) * 1000.0

        ok_count = sum(1 for c in codes if c == 200)
        lat_sorted = sorted(latencies_ms)
        p50 = statistics.median(lat_sorted)
        p95 = lat_sorted[min(len(lat_sorted) - 1, int(0.95 * len(lat_sorted)))]
        print(
            f"[HTTP Burst 70] ok={ok_count}/70 | wall={wall_ms:.1f}ms | "
            f"p50={p50:.1f}ms | p95={p95:.1f}ms | max_concurrent_db_txns={max_active_txns}"
        )
    finally:
        if "prev_pytest_env" in locals():
            if prev_pytest_env is None:
                os.environ.pop("PYTEST_CURRENT_TEST", None)
            else:
                os.environ["PYTEST_CURRENT_TEST"] = prev_pytest_env
        app.dependency_overrides.pop(get_db, None)
        await engine.dispose()
        if os.path.exists(tmp_db_path):
            os.remove(tmp_db_path)


if __name__ == "__main__":
    bench_face_stage(70)
    asyncio.run(bench_http_70_burst())
