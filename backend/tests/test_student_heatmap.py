import uuid
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.core.security import create_access_token, hash_password
from app.database import get_db
from app.main import app
from app.models.attendance import AttendanceRecord, AttendanceSession, Base, Course, User
from app.routers.attendance import user_device_key


async def _setup_db():
    engine = create_async_engine("sqlite+aiosqlite:///:memory:", echo=False)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    session_factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)

    async def override_get_db():
        async with session_factory() as s:
            yield s

    app.dependency_overrides[get_db] = override_get_db
    return engine, session_factory


async def _create_user(
    session_factory,
    *,
    name: str,
    email: str,
    role: str = "STUDENT",
    roll_number: str | None = None,
    program: str = "B.Tech",
    semester: int = 3,
) -> tuple[User, str]:
    async with session_factory() as s:
        u = User(
            id=uuid.uuid4(),
            email=email,
            hashed_password=hash_password("Password123!"),
            full_name=name,
            roll_number=roll_number,
            role=role,
            department="CSE",
            program=program,
            semester=semester,
            section="A",
            is_active=True,
        )
        s.add(u)
        await s.commit()
        await s.refresh(u)
        token = create_access_token({"sub": str(u.id), "role": u.role, "tv": u.token_version})
        return u, token


@pytest.mark.anyio
async def test_student_heatmap_role_gating():
    engine, session_factory = await _setup_db()
    try:
        _, tok_teacher = await _create_user(
            session_factory, name="Teacher", email="t.hm@univ.edu", role="TEACHER"
        )
        _, tok_hod = await _create_user(
            session_factory, name="HOD", email="h.hm@univ.edu", role="HOD"
        )
        _, tok_staff = await _create_user(
            session_factory, name="Staff", email="s.hm@univ.edu", role="STAFF"
        )
        _, tok_student = await _create_user(
            session_factory, name="Student", email="st.hm@univ.edu", role="STUDENT", roll_number="24CSE101"
        )
        _, tok_dev = await _create_user(
            session_factory, name="Dev", email="d.hm@univ.edu", role="DEVELOPER"
        )

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            r_unauth = await client.get("/api/v1/attendance/student/heatmap")
            assert r_unauth.status_code in (401, 403)

            for tok in (tok_teacher, tok_hod, tok_staff):
                r_forbidden = await client.get(
                    "/api/v1/attendance/student/heatmap",
                    headers={"Authorization": f"Bearer {tok}"},
                )
                assert r_forbidden.status_code == 403

            for tok in (tok_student, tok_dev):
                r_ok = await client.get(
                    "/api/v1/attendance/student/heatmap",
                    headers={"Authorization": f"Bearer {tok}"},
                )
                assert r_ok.status_code == 200
                body = r_ok.json()
                assert body["summary"]["total_sessions"] == 0
                assert body["days"] == []
                assert body["course_views"] == {}
    finally:
        app.dependency_overrides.pop(get_db, None)
        await engine.dispose()


@pytest.mark.anyio
async def test_student_heatmap_timezone_variants_and_ist_boundary():
    engine, session_factory = await _setup_db()
    try:
        teacher, _ = await _create_user(
            session_factory, name="Prof Tz", email="tz.t@univ.edu", role="TEACHER"
        )
        student, tok_student = await _create_user(
            session_factory, name="Std Tz", email="tz.s@univ.edu", role="STUDENT", roll_number="24CSE201"
        )

        # Anchor at yesterday 18:30 UTC == today 00:00 IST.
        ist = ZoneInfo("Asia/Kolkata")
        now_ist = datetime.now(timezone.utc).astimezone(ist)
        today_ist_date = now_ist.date()
        # Construct 18:30 UTC on the prior UTC calendar day (= today 00:00 IST)
        midnight_ist_utc = datetime(
            today_ist_date.year, today_ist_date.month, today_ist_date.day, 0, 5, tzinfo=ist
        ).astimezone(timezone.utc)

        async with session_factory() as s:
            course = Course(
                id=uuid.uuid4(),
                course_code="CST-301",
                course_name="Computer Networks • Regular Lecture (Unit 1 • Routing)",
                teacher_id=teacher.id,
            )
            sess = AttendanceSession(
                id=uuid.uuid4(),
                course_id=course.id,
                teacher_id=teacher.id,
                session_token="TZ01",
                status="COMPLETED",
                started_at=midnight_ist_utc,
            )
            rec = AttendanceRecord(
                id=uuid.uuid4(),
                session_id=sess.id,
                student_id=student.id,
                ble_device_id=user_device_key(student),
                status="VERIFIED",
                verification_method="DIRECT_MUTUAL",
            )
            s.add_all([course, sess, rec])
            await s.commit()

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            for params in (
                {"tz": "Asia/Calcutta"},
                {"tz": "GMT+05:30"},
                {"tz": "Invalid/OEM", "tz_offset_minutes": 330},
            ):
                res = await client.get(
                    "/api/v1/attendance/student/heatmap",
                    params=params,
                    headers={"Authorization": f"Bearer {tok_student}"},
                )
                assert res.status_code == 200, res.text
                data = res.json()
                assert len(data["days"]) == 1
                assert data["days"][0]["date"] == today_ist_date.isoformat()
                session_item = data["days"][0]["sessions"][0]
                assert session_item["subject_name"] == "Computer Networks"
                assert session_item["unit"] == "Unit 1"
                assert session_item["topic_covered"] == "Routing"

            bad = await client.get(
                "/api/v1/attendance/student/heatmap",
                params={"tz": "NotAReal/Zone"},
                headers={"Authorization": f"Bearer {tok_student}"},
            )
            assert bad.status_code == 400
    finally:
        app.dependency_overrides.pop(get_db, None)
        await engine.dispose()


@pytest.mark.anyio
async def test_student_heatmap_normalization_levels_streaks_and_course_views():
    engine, session_factory = await _setup_db()
    try:
        teacher, _ = await _create_user(
            session_factory, name="Prof Agg", email="agg.t@univ.edu", role="TEACHER"
        )
        student, tok_student = await _create_user(
            session_factory, name="Std Agg", email="agg.s@univ.edu", role="STUDENT", roll_number="24CSE301"
        )

        now_ist = datetime.now(timezone.utc).astimezone(ZoneInfo("Asia/Kolkata"))
        d4_ago = (now_ist - timedelta(days=4)).replace(hour=10, minute=0, second=0, microsecond=0).astimezone(timezone.utc)
        d3_ago = (now_ist - timedelta(days=3)).replace(hour=10, minute=0, second=0, microsecond=0).astimezone(timezone.utc)
        d2_ago = (now_ist - timedelta(days=2)).replace(hour=10, minute=0, second=0, microsecond=0).astimezone(timezone.utc)
        d1_ago = (now_ist - timedelta(days=1)).replace(hour=10, minute=0, second=0, microsecond=0).astimezone(timezone.utc)
        d0 = now_ist.replace(hour=10, minute=0, second=0, microsecond=0).astimezone(timezone.utc)

        async with session_factory() as s:
            c1 = Course(id=uuid.uuid4(), course_code="CS-101", course_name="Algorithms", teacher_id=teacher.id, course_type="THEORY")
            c2 = Course(id=uuid.uuid4(), course_code="CS-102", course_name="OS Lab", teacher_id=teacher.id, course_type="LAB")
            s.add_all([c1, c2])

            def add_sess(cid, tok, started, st_sess, rec_status, vm="DIRECT_MUTUAL"):
                sid = uuid.uuid4()
                s.add(
                    AttendanceSession(
                        id=sid,
                        course_id=cid,
                        teacher_id=teacher.id,
                        session_token=tok,
                        status=st_sess,
                        started_at=started,
                    )
                )
                s.add(
                    AttendanceRecord(
                        id=uuid.uuid4(),
                        session_id=sid,
                        student_id=student.id,
                        ble_device_id=f"{user_device_key(student)}-{tok}"[:50],
                        status=rec_status,
                        verification_method=vm,
                    )
                )

            # Day -4: 1 unverified + 1 absent -> 2 ABSENT (streak=0)
            add_sess(c1.id, "S001", d4_ago, "COMPLETED", "UNVERIFIED")
            add_sess(c2.id, "S002", d4_ago + timedelta(hours=1), "COMPLETED", "COGNITIVE_CHALLENGE_FAILED")

            # Day -3: 1 PRESENT (level 3 since ratio=1.0, present < 3) -> streak=1
            add_sess(c1.id, "S003", d3_ago, "COMPLETED", "VERIFIED")

            # Day -2: LEAVE only -> streak stays 1 (neither extends nor breaks)
            add_sess(c1.id, "S004", d2_ago, "COMPLETED", "LEAVE", vm="LEAVE_APPROVAL")

            # Day -1: 1 VERIFIED_PRESENT (CS-101) + 2 VERIFIED (CS-102) -> 3 PRESENT -> level 4, streak=2
            add_sess(c1.id, "S005", d1_ago, "COMPLETED", "VERIFIED_PRESENT")
            add_sess(c2.id, "S006", d1_ago + timedelta(hours=1), "COMPLETED", "VERIFIED")
            add_sess(c2.id, "S007", d1_ago + timedelta(hours=2), "COMPLETED", "VERIFIED")

            # Today: non-COMPLETED sessions MUST be excluded
            add_sess(c1.id, "S008", d0, "ACTIVE", "VERIFIED")
            add_sess(c1.id, "S009", d0 + timedelta(minutes=5), "CANCELLED", "ABSENT")
            add_sess(c1.id, "S010", d0 + timedelta(minutes=10), "EXPIRED", "ABSENT")

            await s.commit()

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            res = await client.get(
                "/api/v1/attendance/student/heatmap",
                params={"tz": "Asia/Kolkata"},
                headers={"Authorization": f"Bearer {tok_student}"},
            )
            assert res.status_code == 200, res.text
            body = res.json()

            # Overall: 4 present, 2 absent, 1 leave -> 4/6 = 66.7%, streak=2, longest=2
            summary = body["summary"]
            assert summary["total_sessions"] == 7
            assert summary["present_count"] == 4
            assert summary["absent_count"] == 2
            assert summary["leave_count"] == 1
            assert summary["attendance_percentage"] == 66.7
            assert summary["current_streak_days"] == 2
            assert summary["longest_streak_days"] == 2

            by_date = {d["date"]: d for d in body["days"]}
            assert len(by_date) == 4
            d3_str = d3_ago.astimezone(ZoneInfo("Asia/Kolkata")).date().isoformat()
            d1_str = d1_ago.astimezone(ZoneInfo("Asia/Kolkata")).date().isoformat()
            assert by_date[d3_str]["level"] == 3
            assert by_date[d1_str]["level"] == 4

            # Per-course precomputed views parity check
            cs101 = body["course_views"]["CS-101"]["summary"]
            assert cs101["present_count"] == 2
            assert cs101["absent_count"] == 1
            assert cs101["leave_count"] == 1
            assert cs101["attendance_percentage"] == 66.7
            assert cs101["current_streak_days"] == 2

            cs102 = body["course_views"]["CS-102"]["summary"]
            assert cs102["present_count"] == 2
            assert cs102["absent_count"] == 1
            assert cs102["leave_count"] == 0
            assert cs102["attendance_percentage"] == 66.7
    finally:
        app.dependency_overrides.pop(get_db, None)
        await engine.dispose()


@pytest.mark.anyio
async def test_student_heatmap_colliding_device_key_isolation_and_precedence():
    engine, session_factory = await _setup_db()
    try:
        teacher, _ = await _create_user(
            session_factory, name="Prof Col", email="col.t@univ.edu", role="TEACHER"
        )
        student_a, tok_a = await _create_user(
            session_factory, name="Student A", email="cola@univ.edu", role="STUDENT", roll_number="24CSE901"
        )
        student_b, _ = await _create_user(
            session_factory, name="Student B", email="colb@univ.edu", role="STUDENT", roll_number="24CSE902"
        )

        key_a = user_device_key(student_a)
        started = datetime.now(timezone.utc) - timedelta(hours=2)

        async with session_factory() as s:
            course = Course(id=uuid.uuid4(), course_code="CS-900", course_name="Collision Test", teacher_id=teacher.id)
            s.add(course)

            # Session 1: row belongs explicitly to Student B even with colliding key_a -> A must NEVER see it
            s1 = AttendanceSession(
                id=uuid.uuid4(), course_id=course.id, teacher_id=teacher.id, session_token="COL1", status="COMPLETED", started_at=started
            )
            s.add(s1)
            s.add(
                AttendanceRecord(
                    id=uuid.uuid4(),
                    session_id=s1.id,
                    student_id=student_b.id,
                    ble_device_id=key_a,
                    status="VERIFIED",
                    verification_method="DIRECT_MUTUAL",
                )
            )

            # Session 2: A has a bound PRESENT row AND an orphan unbound ABSENT row with colliding key_a
            # Bound row MUST win regardless of query/row ordering.
            s2 = AttendanceSession(
                id=uuid.uuid4(), course_id=course.id, teacher_id=teacher.id, session_token="COL2", status="COMPLETED", started_at=started - timedelta(days=1)
            )
            s.add(s2)
            s.add(
                AttendanceRecord(
                    id=uuid.uuid4(),
                    session_id=s2.id,
                    student_id=None,
                    ble_device_id=key_a,
                    status="ABSENT",
                    verification_method="NONE",
                )
            )
            s.add(
                AttendanceRecord(
                    id=uuid.uuid4(),
                    session_id=s2.id,
                    student_id=student_a.id,
                    ble_device_id="ALTKEY",
                    status="VERIFIED",
                    verification_method="MANUAL_OVERRIDE",
                )
            )
            await s.commit()

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            res = await client.get(
                "/api/v1/attendance/student/heatmap",
                headers={"Authorization": f"Bearer {tok_a}"},
            )
            assert res.status_code == 200, res.text
            body = res.json()
            assert body["summary"]["total_sessions"] == 1
            assert body["summary"]["present_count"] == 1
            assert body["summary"]["absent_count"] == 0
            assert body["days"][0]["sessions"][0]["session_id"] == str(s2.id)
            assert body["days"][0]["sessions"][0]["status"] == "PRESENT"
    finally:
        app.dependency_overrides.pop(get_db, None)
        await engine.dispose()
