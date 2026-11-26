from __future__ import annotations

import asyncio
import logging
import uuid
from datetime import datetime, timedelta, timezone
from typing import Optional

from sqlalchemy import and_, func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.attendance import AttendanceRecord, AttendanceSession, Course, LeaveApplication, User
from app.services.face_engine import face_registry
from app.services.graph_engine import audit_classroom_mesh

logger = logging.getLogger(__name__)

# Bounded dynamic RSSI calibration corridor [-86, -78] dBm around -82 dBm default.
RSSI_CUTOFF_DEFAULT = -82
RSSI_CUTOFF_MIN = -86
RSSI_CUTOFF_MAX = -78
_GRAPH_ONLY_METHODS = {"NONE", "BELOW_THRESHOLD", "MESH_UNANCHORED"}


def clamp_rssi_cutoff(value: Optional[int]) -> int:
    if value is None:
        return RSSI_CUTOFF_DEFAULT
    return max(RSSI_CUTOFF_MIN, min(RSSI_CUTOFF_MAX, int(value)))


def compute_next_rssi_cutoff(
    current_cutoff: Optional[int],
    session_summaries: list[tuple[int, int, int]],
) -> int:
    """Adjust course RSSI threshold by at most +-1 dBm within [-86, -78].

    session_summaries is ordered newest-first as:
      (submitted_count, graph_false_negative_overrides, false_positive_overrides)
    """
    cur = clamp_rssi_cutoff(current_cutoff)
    if not session_summaries:
        return cur
    submitted, graph_fn, fp_unmarks = session_summaries[0]
    if submitted < 3:
        return cur
    if fp_unmarks >= 1:
        return clamp_rssi_cutoff(cur + 1)
    fn_trigger = max(1, (submitted * 5 + 99) // 100)
    if graph_fn >= fn_trigger:
        return clamp_rssi_cutoff(cur - 1)
    # Self-heal back toward -82 dBm after 3 clean zero-override classes.
    if (
        cur < RSSI_CUTOFF_DEFAULT
        and len(session_summaries) >= 3
        and all(s >= 3 and gfn == 0 and fp == 0 for s, gfn, fp in session_summaries[:3])
    ):
        return min(RSSI_CUTOFF_DEFAULT, cur + 1)
    return cur


async def calibrate_course_rssi_cutoff(course_obj: Course, db: AsyncSession) -> int:
    cur = clamp_rssi_cutoff(getattr(course_obj, "rssi_cutoff", None))
    sess_res = await db.execute(
        select(AttendanceSession)
        .where(
            AttendanceSession.course_id == course_obj.id,
            AttendanceSession.audit_run_at.is_not(None),
        )
        .order_by(AttendanceSession.started_at.desc())
        .limit(5)
    )
    sessions = list(sess_res.scalars().all())
    if not sessions:
        return cur

    summaries: list[tuple[int, int, int]] = []
    newest_eligible_id: Optional[uuid.UUID] = None
    for s in sessions:
        rec_res = await db.execute(
            select(AttendanceRecord).where(AttendanceRecord.session_id == s.id)
        )
        recs = list(rec_res.scalars().all())
        submitted = [r for r in recs if r.status not in ("ABSENT", "LEAVE")]
        if len(submitted) < 3:
            continue
        if newest_eligible_id is None:
            newest_eligible_id = s.id
        graph_fn = sum(
            1
            for r in recs
            if r.verification_method == "MANUAL_OVERRIDE"
            and r.pre_override_status == "UNVERIFIED"
            and (r.pre_override_method or "") in _GRAPH_ONLY_METHODS
            and r.status in ("VERIFIED", "VERIFIED_PRESENT")
        )
        fp_unmarks = sum(
            1
            for r in recs
            if r.verification_method == "MANUAL_OVERRIDE"
            and (r.pre_override_status or "") in ("VERIFIED", "VERIFIED_PRESENT")
            and r.status in ("UNVERIFIED", "ABSENT")
        )
        summaries.append((len(submitted), graph_fn, fp_unmarks))
        if len(summaries) >= 3:
            break

    if newest_eligible_id is None or course_obj.rssi_calibrated_session_id == newest_eligible_id:
        return cur

    updated = compute_next_rssi_cutoff(cur, summaries)
    course_obj.rssi_cutoff = updated
    course_obj.rssi_calibrated_session_id = newest_eligible_id
    return updated

# In-memory scheduler registry: session_id -> (audit_due_at, lock_due_at)
# Event-driven only; no periodic DB polling. Rebuilt once at startup.
_schedule: dict[str, tuple[datetime, datetime]] = {}
_schedule_lock = asyncio.Lock()
_schedule_event = asyncio.Event()

# Per-session audit/lock serialization in-process.
_audit_locks: dict[str, asyncio.Lock] = {}
_audit_locks_guard = asyncio.Lock()

# Frozen security verdicts written out-of-band; audit must not overwrite them.
_FROZEN_STATUSES = {
    "DUPLICATE_FACE_PROXY",
    "CO_LOCATED_SAME_DESK_PROXY",
    "DIVIDED_ATTENTION_PROXY",
    "COGNITIVE_CHALLENGE_FAILED",
    "NO_FACE_DETECTED",
    "SPOOFER_REJECTED",
    "ELIGIBILITY_REJECTED",
    # F1 1:1 face mismatch is remark-grade: held for the teacher's finalize
    # review, never auto-cleared or auto-rejected by the audit engine.
    "IDENTITY_MISMATCH_REVIEW",
}


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


def _aware(dt: Optional[datetime]) -> Optional[datetime]:
    if dt is None:
        return None
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def derive_short_device_key_local(canonical: str) -> str:
    # Mirror router/app derivation to avoid circular imports.
    import hashlib
    src = (canonical or "").strip().upper()
    if not src:
        return "000000"
    digest = hashlib.sha256(src.encode("utf-8")).hexdigest().upper()
    return digest[:6]


def _program_key(value: Optional[str]) -> str:
    return "".join(ch for ch in (value or "").upper() if ch.isalnum())


async def _get_audit_lock(session_id: str) -> asyncio.Lock:
    async with _audit_locks_guard:
        lock = _audit_locks.get(session_id)
        if lock is None:
            lock = asyncio.Lock()
            _audit_locks[session_id] = lock
        return lock


async def register_session_schedule(session_id: str, started_at: datetime, game_ends_at: Optional[datetime]) -> None:
    started = _aware(started_at) or _utcnow()
    lock_due = started + timedelta(seconds=60)
    audit_due = (_aware(game_ends_at) + timedelta(seconds=7, microseconds=500000)) if game_ends_at else (_aware(started_at) + timedelta(seconds=45) if started_at else lock_due)
    async with _schedule_lock:
        _schedule[session_id] = (audit_due, lock_due)
    _schedule_event.set()


async def unregister_session_schedule(session_id: str) -> None:
    async with _schedule_lock:
        _schedule.pop(session_id, None)
    _schedule_event.set()


async def rebuild_schedule_from_db(db: AsyncSession) -> None:
    rows = await db.execute(
        select(AttendanceSession).where(
            AttendanceSession.status == "ACTIVE",
            AttendanceSession.started_at.is_not(None),
        )
    )
    async with _schedule_lock:
        _schedule.clear()
        for s in rows.scalars().all():
            started = _aware(s.started_at) or _utcnow()
            lock_due = started + timedelta(seconds=60)
            ge = _aware(s.game_ends_at)
            audit_due = (ge + timedelta(seconds=7, microseconds=500000)) if ge else (started + timedelta(seconds=45))
            _schedule[str(s.id)] = (audit_due, lock_due)
    _schedule_event.set()


def _next_wake(now: datetime) -> Optional[datetime]:
    if not _schedule:
        return None
    soonest = None
    for audit_due, lock_due in _schedule.values():
        due = min(audit_due, lock_due)
        soonest = due if soonest is None else min(soonest, due)
    return soonest


async def _recompute_session_counters(session_obj: AttendanceSession, db: AsyncSession) -> None:
    await db.flush()
    session_obj.total_verified = int((await db.execute(
        select(func.count()).select_from(AttendanceRecord).where(
            AttendanceRecord.session_id == session_obj.id,
            AttendanceRecord.status.in_(["VERIFIED", "VERIFIED_PRESENT"]),
        )
    )).scalar_one() or 0)
    session_obj.total_unverified = int((await db.execute(
        select(func.count()).select_from(AttendanceRecord).where(
            AttendanceRecord.session_id == session_obj.id,
            AttendanceRecord.status.not_in(["VERIFIED", "VERIFIED_PRESENT", "LEAVE"]),
        )
    )).scalar_one() or 0)


async def _load_eligible_roster(session_obj: AttendanceSession, db: AsyncSession) -> list[User]:
    semester = session_obj.eligibility_semester
    program = (session_obj.eligibility_program or "").strip()
    if semester is None or not program:
        return []
    candidates = await db.execute(
        select(User).where(
            User.semester == semester,
            User.is_active.is_(True),
            User.role == "STUDENT",
        )
    )
    prog_key = _program_key(program)
    return [u for u in candidates.scalars().all() if u.roll_number and _program_key(u.program) == prog_key]


async def _apply_leave_overlay(
    session_obj: AttendanceSession,
    db: AsyncSession,
    roster: Optional[list[User]] = None,
) -> int:
    started = _aware(session_obj.started_at)
    if started is None:
        return 0
    session_date = started.date()
    session_date_ist = (started + timedelta(hours=5, minutes=30)).date()

    if roster is None:
        roster = await _load_eligible_roster(session_obj, db)
    if not roster:
        return 0
    roster_ids = [u.id for u in roster]
    coverage = [and_(LeaveApplication.from_date <= session_date, LeaveApplication.to_date >= session_date)]
    if session_date_ist != session_date:
        coverage.append(and_(LeaveApplication.from_date <= session_date_ist, LeaveApplication.to_date >= session_date_ist))
    leave_res = await db.execute(
        select(LeaveApplication).where(
            LeaveApplication.student_id.in_(roster_ids),
            LeaveApplication.status == "APPROVED",
            or_(*coverage),
        ).order_by(LeaveApplication.created_at)
    )
    leave_by_student: dict[uuid.UUID, LeaveApplication] = {}
    for lr in leave_res.scalars().all():
        leave_by_student[lr.student_id] = lr
    if not leave_by_student:
        return 0

    rec_res = await db.execute(select(AttendanceRecord).where(AttendanceRecord.session_id == session_obj.id))
    existing_recs = rec_res.scalars().all()
    by_student = {r.student_id: r for r in existing_recs if r.student_id}
    by_device = {r.ble_device_id.upper(): r for r in existing_recs}
    created = 0
    for student in roster:
        leave_row = leave_by_student.get(student.id)
        if not leave_row:
            continue
        range_text = f"{leave_row.from_date.isoformat()}..{leave_row.to_date.isoformat()}"
        rec = by_student.get(student.id) or by_device.get(derive_short_device_key_local(student.roll_number))
        if rec is None:
            db.add(AttendanceRecord(
                id=uuid.uuid4(), session_id=session_obj.id, student_id=student.id,
                ble_device_id=derive_short_device_key_local(student.roll_number),
                status="LEAVE", verification_method="LEAVE_APPROVAL", affinity_score=0.00,
                reported_neighbors=[], remarks=f"Approved leave {range_text}", recorded_at=_utcnow(),
            ))
            created += 1
        elif rec.status == "ABSENT":
            rec.status = "LEAVE"
            rec.verification_method = "LEAVE_APPROVAL"
            rec.remarks = f"Approved leave {range_text}"
    if created:
        await db.flush()
    return created


async def _absent_backfill(
    session_obj: AttendanceSession,
    db: AsyncSession,
    roster: Optional[list[User]] = None,
) -> int:
    if roster is None:
        roster = await _load_eligible_roster(session_obj, db)
    if not roster:
        return 0
    rec_res = await db.execute(select(AttendanceRecord).where(AttendanceRecord.session_id == session_obj.id))
    existing = rec_res.scalars().all()
    by_student = {r.student_id for r in existing if r.student_id}
    by_device = {r.ble_device_id.upper() for r in existing}
    created = 0
    for u in roster:
        key = derive_short_device_key_local(u.roll_number)
        if u.id in by_student or key in by_device:
            continue
        db.add(AttendanceRecord(
            id=uuid.uuid4(), session_id=session_obj.id, student_id=u.id, ble_device_id=key,
            status="ABSENT", verification_method="NONE", affinity_score=0.00,
            reported_neighbors=[], remarks="No check-in received", recorded_at=_utcnow(),
        ))
        created += 1
    if created:
        await db.flush()
    return created


async def _bind_unbound_records(
    session_obj: AttendanceSession,
    db: AsyncSession,
    roster: Optional[list[User]] = None,
) -> int:
    if roster is None:
        roster = await _load_eligible_roster(session_obj, db)
    key_to_student = {
        derive_short_device_key_local(u.roll_number): u.id
        for u in roster
        if u.roll_number
    }
    if not key_to_student:
        return 0
    rec_res = await db.execute(select(AttendanceRecord).where(
        AttendanceRecord.session_id == session_obj.id, AttendanceRecord.student_id.is_(None)
    ))
    bound = 0
    for rec in rec_res.scalars().all():
        uid = key_to_student.get(rec.ble_device_id.upper())
        if uid:
            rec.student_id = uid
            bound += 1
    return bound


async def run_engine_audit(session_id: uuid.UUID | str, db: AsyncSession) -> bool:
    """Run mesh audit once on committed records. Returns True if audit committed this call."""
    sid = str(session_id)
    sid_uuid = session_id if isinstance(session_id, uuid.UUID) else uuid.UUID(sid)
    lock = await _get_audit_lock(sid)
    async with lock:
        sess_res = await db.execute(select(AttendanceSession).where(AttendanceSession.id == sid_uuid).with_for_update())
        session_obj = sess_res.scalars().first()
        if not session_obj:
            return False
        if session_obj.audit_run_at is not None:
            return False
        if session_obj.status in ("CANCELLED", "EXPIRED"):
            return False

        rec_res = await db.execute(select(AttendanceRecord).where(AttendanceRecord.session_id == session_obj.id))
        records = rec_res.scalars().all()
        by_device = {r.ble_device_id.upper(): r for r in records}

        student_ids = {r.student_id for r in records if r.student_id}
        role_by_id: dict[uuid.UUID, str] = {}
        if student_ids:
            role_res = await db.execute(select(User.id, User.role).where(User.id.in_(student_ids)))
            role_by_id = {uid: (role or "").upper() for uid, role in role_res.all()}

        # Build reports only from eligible student rows that passed the cognitive challenge.
        # Excluding non-student callers (TEACHER/DEVELOPER/STAFF) and score < 15 / missing
        # scores pre-graph prevents diagnostic/unplayed phones from corroborating peers or
        # inflating submission counts toward oversubscription.
        combined_reports: dict[str, list[str]] = {}
        pre_failed_challenge: list[AttendanceRecord] = []
        non_student_records: list[AttendanceRecord] = []
        for r in records:
            if r.status in _FROZEN_STATUSES:
                continue
            if r.student_id and role_by_id.get(r.student_id, "STUDENT") != "STUDENT":
                non_student_records.append(r)
                continue
            if r.status not in ("ABSENT", "LEAVE") and r.reported_neighbors is not None:
                if r.game_score is None or r.game_score < 15:
                    pre_failed_challenge.append(r)
                    continue
                combined_reports[r.ble_device_id.upper()] = [str(n).strip().upper() for n in r.reported_neighbors]

        audit_results = audit_classroom_mesh(
            combined_reports,
            total_enrolled=session_obj.total_enrolled or 0,
        ) if combined_reports else []

        # Flag faces that registry marked as duplicate pairs (post-mesh so innocent
        # classmates who sat near a compromised/first-submitter key stay VERIFIED).
        try:
            flagged_pairs = face_registry.get_flagged_pairs(sid)
            flagged_keys = set()
            for p in flagged_pairs:
                # face_engine writes student_a_key/student_b_key; accept legacy
                # student_key/matched_with shapes for in-memory pairs created
                # by older builds.
                a = p.get("student_a_key") or p.get("student_key")
                b = p.get("student_b_key") or p.get("matched_with")
                if a:
                    flagged_keys.add(str(a).upper())
                if b:
                    flagged_keys.add(str(b).upper())
        except Exception:
            flagged_keys = set()

        result_by_device = {item["ble_device_id"].upper(): item for item in audit_results}
        non_student_devs: set[str] = set()
        for rec in non_student_records:
            non_student_devs.add(rec.ble_device_id.upper())
            rec.status = "UNVERIFIED"
            rec.verification_method = "NON_STUDENT_DIAGNOSTIC"
            rec.affinity_score = 0.0
            rec.remarks = "Diagnostic non-student submission excluded from attendance"
        for rec in pre_failed_challenge:
            score = rec.game_score
            rec.status = "COGNITIVE_CHALLENGE_FAILED"
            rec.verification_method = "COGNITIVE_CHALLENGE"
            rec.affinity_score = 0.0
            rec.remarks = f"Chimp challenge failed: score {score if score is not None else 'missing'} < 15"

        for dev, item in result_by_device.items():
            if dev in flagged_keys and item.get("status") == "VERIFIED":
                item["status"] = "UNVERIFIED"
                item["verification_method"] = "FACE_BIOMETRIC_MATCH"
                item["remarks"] = "Duplicate face biometric flagged - Teacher check"

        roster = await _load_eligible_roster(session_obj, db)
        roster_by_key = {
            derive_short_device_key_local(u.roll_number): u
            for u in roster
            if u.roll_number
        }
        witness_created = False
        for dev, item in result_by_device.items():
            if dev in non_student_devs:
                continue
            rec = by_device.get(dev)
            if rec is None:
                # Persist offline PEER_WITNESS only for enrolled roster members so
                # dropped uploads still surface for teacher review without ghost rows.
                roster_user = (
                    roster_by_key.get(dev)
                    if item.get("verification_method") == "PEER_WITNESS"
                    else None
                )
                if roster_user is None:
                    continue
                bridge = item.get("bridge_device_id")
                rec = AttendanceRecord(
                    id=uuid.uuid4(),
                    session_id=session_obj.id,
                    student_id=roster_user.id,
                    ble_device_id=dev,
                    status=item["status"],
                    verification_method="PEER_WITNESS",
                    affinity_score=float(item.get("affinity_score", 0.0) or 0.0),
                    bridge_device_id=str(bridge).upper() if bridge else None,
                    reported_neighbors=item.get("reported_neighbors") or [],
                    remarks=item.get("remarks"),
                    recorded_at=_utcnow(),
                )
                db.add(rec)
                by_device[dev] = rec
                witness_created = True
                continue
            if rec.status in _FROZEN_STATUSES:
                continue
            rec.status = item["status"]
            rec.verification_method = item.get("verification_method", "NONE")
            rec.affinity_score = float(item.get("affinity_score", 0.0) or 0.0)
            rec.remarks = item.get("remarks")
            rec.reported_neighbors = item.get("reported_neighbors") or rec.reported_neighbors or []
            bridge = item.get("bridge_device_id")
            if bridge:
                rec.bridge_device_id = str(bridge).upper()
            score = item.get("game_score")
            if score is not None:
                rec.game_score = score if rec.game_score is None else max(rec.game_score, score)

        if witness_created:
            await db.flush()
        await _absent_backfill(session_obj, db, roster=roster)
        await _bind_unbound_records(session_obj, db, roster=roster)
        await _apply_leave_overlay(session_obj, db, roster=roster)
        await _recompute_session_counters(session_obj, db)

        # Stamp audit_run_at in the SAME transaction as verdicts.
        session_obj.audit_run_at = _utcnow()
        await db.commit()
        logger.info("[Audit Run] Session %s audited (%d verdict rows)", sid, len(result_by_device))
        return True


async def lock_session(session_id: uuid.UUID | str, db: AsyncSession) -> bool:
    sid = str(session_id)
    sid_uuid = session_id if isinstance(session_id, uuid.UUID) else uuid.UUID(sid)
    # Ensure audit exists before locking.
    sess_res = await db.execute(select(AttendanceSession).where(AttendanceSession.id == sid_uuid))
    session_obj = sess_res.scalars().first()
    if not session_obj:
        return False
    if session_obj.status == "COMPLETED":
        return False
    if session_obj.status in ("CANCELLED", "EXPIRED"):
        return False
    if session_obj.audit_run_at is None:
        await run_engine_audit(session_id, db)

    lock = await _get_audit_lock(sid)
    async with lock:
        sess_res = await db.execute(select(AttendanceSession).where(AttendanceSession.id == session_obj.id).with_for_update())
        session_obj = sess_res.scalars().first()
        # Re-check under the row lock: CANCELLED/EXPIRED may have landed
        # between the unlocked read above and this re-select.
        if not session_obj or session_obj.status in ("COMPLETED", "CANCELLED", "EXPIRED"):
            return False
        await _recompute_session_counters(session_obj, db)
        session_obj.status = "COMPLETED"
        session_obj.ended_at = _utcnow()
        await db.commit()
        try:
            face_registry.clear_session(sid)
        except Exception:
            pass
        await unregister_session_schedule(sid)
        logger.info("[Session Locked] Session %s completed", sid)
        return True


async def maybe_run_due_audit(session_obj: AttendanceSession, db: AsyncSession) -> None:
    if session_obj.status not in ("ACTIVE",):
        return
    if session_obj.audit_run_at is not None:
        return
    ge = _aware(session_obj.game_ends_at)
    if ge is None:
        # Legacy session: no schedule
        return
    if _utcnow() >= ge + timedelta(seconds=7, microseconds=500000):
        await run_engine_audit(session_obj.id, db)


async def maybe_run_due_lock(session_obj: AttendanceSession, db: AsyncSession) -> None:
    if session_obj.status != "ACTIVE":
        return
    started = _aware(session_obj.started_at)
    if started is None:
        return
    if _utcnow() >= started + timedelta(seconds=60):
        await lock_session(session_obj.id, db)


async def audit_scheduler_worker(session_factory) -> None:
    try:
        while True:
            now = _utcnow()
            async with _schedule_lock:
                due_sids = [
                    sid for sid, (audit_due, lock_due) in list(_schedule.items())
                    if now >= audit_due or now >= lock_due
                ]
                wake = _next_wake(now)
            for sid in due_sids:
                try:
                    async with session_factory() as db:
                        sres = await db.execute(select(AttendanceSession).where(AttendanceSession.id == uuid.UUID(sid)))
                        sobj = sres.scalars().first()
                        if sobj:
                            await maybe_run_due_audit(sobj, db)
                            await db.refresh(sobj)
                            await maybe_run_due_lock(sobj, db)
                            if sobj.status in ("COMPLETED", "CANCELLED", "EXPIRED"):
                                await unregister_session_schedule(sid)
                except Exception as e:
                    logger.warning("[Scheduler] tick failed for %s: %s", sid, e)
            if wake is None:
                _schedule_event.clear()
                try:
                    await asyncio.wait_for(_schedule_event.wait(), timeout=300)
                except asyncio.TimeoutError:
                    pass
            else:
                delay = max(0.2, (wake - _utcnow()).total_seconds())
                _schedule_event.clear()
                try:
                    await asyncio.wait_for(_schedule_event.wait(), timeout=min(delay, 300))
                except asyncio.TimeoutError:
                    pass
    except asyncio.CancelledError:
        return
