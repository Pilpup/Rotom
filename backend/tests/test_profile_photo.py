"""Owner-only profile photo: processing, storage, serving and abuse cases."""

import base64
import io
import uuid

import pytest
from httpx import ASGITransport, AsyncClient
from PIL import Image
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.database import get_db
from app.main import app
from app.models.attendance import Base, EmailOTP, UserProfilePhoto


async def _make_client():
    engine = create_async_engine("sqlite+aiosqlite:///:memory:", echo=False)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)

    async def override_get_db():
        async with factory() as session:
            yield session

    app.dependency_overrides[get_db] = override_get_db
    client = AsyncClient(transport=ASGITransport(app=app), base_url="http://test")
    return client, factory, engine


async def _register(client, factory, tag):
    email = f"photo-{tag}@univ.edu"
    res = await client.post(
        "/api/v1/auth/register",
        json={
            "name": f"Photo User {tag}",
            "email": email,
            "password": "Password123!",
            "role": "STUDENT",
            "roll_number": f"PH{tag.upper()}",
            "department": "CSE",
            "program": "B.Tech",
            "semester": 1,
            "section": "A",
        },
    )
    assert res.status_code == 200, res.text
    async with factory() as session:
        otp = (
            await session.execute(
                select(EmailOTP).where(
                    EmailOTP.email == email, EmailOTP.purpose == "REGISTER"
                )
            )
        ).scalars().first()
        code = otp.otp_code
    verified = await client.post(
        "/api/v1/auth/register/verify-otp",
        json={"email": email, "otp_code": code},
    )
    assert verified.status_code == 201, verified.text
    return {"Authorization": f"Bearer {verified.json()['access_token']}"}


def _jpeg_bytes(size=(400, 200), color=(120, 80, 200)):
    img = Image.new("RGB", size, color=color)
    # A wide source proves the server center-crops to the fixed 1:1 ratio.
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    return buf.getvalue()


def _b64(raw: bytes) -> str:
    return base64.b64encode(raw).decode("ascii")


@pytest.mark.anyio
async def test_profile_photo_roundtrip_and_fixed_ratio():
    client, factory, engine = await _make_client()
    try:
        headers = await _register(client, factory, "round")
        res = await client.put(
            "/api/v1/auth/me/profile-photo",
            headers=headers,
            json={"image_base64": _b64(_jpeg_bytes())},
        )
        assert res.status_code == 200, res.text
        body = res.json()
        assert body["profile_photo_url"].startswith("api/v1/auth/me/profile-photo?v=")
        palette = body["profile_palette"]
        assert all(
            isinstance(palette[k], str) and len(palette[k]) == 7 and palette[k][0] == "#"
            for k in ("primary", "secondary", "tertiary")
        )

        fetched = await client.get(body["profile_photo_url"], headers=headers)
        assert fetched.status_code == 200
        assert fetched.headers["content-type"].startswith("image/jpeg")
        assert fetched.headers["cache-control"] == "private, max-age=31536000, immutable"
        assert fetched.headers["x-content-type-options"] == "nosniff"
        img = Image.open(io.BytesIO(fetched.content))
        assert img.size == (256, 256)

        me = await client.get("/api/v1/auth/me", headers=headers)
        assert me.json()["profile_photo_url"] == body["profile_photo_url"]

        deleted = await client.delete("/api/v1/auth/me/profile-photo", headers=headers)
        assert deleted.status_code == 200
        assert deleted.json()["profile_photo_url"] is None
        missing = await client.get("/api/v1/auth/me/profile-photo", headers=headers)
        assert missing.status_code == 404
    finally:
        app.dependency_overrides.clear()
        await client.aclose()
        await engine.dispose()


@pytest.mark.anyio
async def test_profile_photo_rejects_unsupported_and_oversize():
    client, factory, engine = await _make_client()
    try:
        headers = await _register(client, factory, "bad")

        no_auth = await client.put(
            "/api/v1/auth/me/profile-photo", json={"image_base64": _b64(_jpeg_bytes())}
        )
        assert no_auth.status_code == 401

        svg = await client.put(
            "/api/v1/auth/me/profile-photo",
            headers=headers,
            json={"image_base64": _b64(b"<svg onload=alert(1)>")},
        )
        assert svg.status_code == 400

        gif = await client.put(
            "/api/v1/auth/me/profile-photo",
            headers=headers,
            json={"image_base64": _b64(b"GIF89a" + b"\x00" * 32)},
        )
        assert gif.status_code == 400

        corrupt = await client.put(
            "/api/v1/auth/me/profile-photo",
            headers=headers,
            json={"image_base64": _b64(b"\xff\xd8\xff" + b"not a jpeg" * 20)},
        )
        assert corrupt.status_code == 400

        oversize = await client.put(
            "/api/v1/auth/me/profile-photo",
            headers=headers,
            json={
                "image_base64": _b64(
                    b"\xff\xd8\xff" + b"\x00" * (2 * 1024 * 1024 + 1024)
                )
            },
        )
        assert oversize.status_code == 413

        malformed = await client.put(
            "/api/v1/auth/me/profile-photo",
            headers=headers,
            json={"image_base64": "not base64!!"},
        )
        assert malformed.status_code == 400
    finally:
        app.dependency_overrides.clear()
        await client.aclose()
        await engine.dispose()


@pytest.mark.anyio
async def test_profile_photo_is_owner_only_and_no_public_id_route():
    client, factory, engine = await _make_client()
    try:
        alice = await _register(client, factory, "alice")
        bob = await _register(client, factory, "bob")
        res = await client.put(
            "/api/v1/auth/me/profile-photo",
            headers=alice,
            json={"image_base64": _b64(_jpeg_bytes())},
        )
        assert res.status_code == 200

        # No cross-user URL exists at all.
        public = await client.get(f"/api/v1/users/{uuid.uuid4()}/profile-photo", headers=bob)
        assert public.status_code == 404

        # Bob's own profile has no photo reference to Alice's image.
        bob_me = await client.get("/api/v1/auth/me", headers=bob)
        assert bob_me.json()["profile_photo_url"] is None

        # Bob's own avatar endpoint stays 404.
        bob_photo = await client.get("/api/v1/auth/me/profile-photo", headers=bob)
        assert bob_photo.status_code == 404

        async with factory() as session:
            rows = (await session.execute(select(UserProfilePhoto))).scalars().all()
            assert len(rows) == 1
    finally:
        app.dependency_overrides.clear()
        await client.aclose()
        await engine.dispose()


@pytest.mark.anyio
async def test_profile_photo_replaces_and_drops_metadata():
    client, factory, engine = await _make_client()
    try:
        headers = await _register(client, factory, "replace")
        first = await client.put(
            "/api/v1/auth/me/profile-photo",
            headers=headers,
            json={"image_base64": _b64(_jpeg_bytes(color=(200, 40, 40)))},
        )
        assert first.status_code == 200
        second = await client.put(
            "/api/v1/auth/me/profile-photo",
            headers=headers,
            json={"image_base64": _b64(_jpeg_bytes(color=(30, 120, 200)))},
        )
        assert second.status_code == 200
        assert (
            second.json()["profile_photo_url"]
            != first.json()["profile_photo_url"]
        )

        fetched = await client.get(second.json()["profile_photo_url"], headers=headers)
        img = Image.open(io.BytesIO(fetched.content))
        assert img.size == (256, 256)
        # Re-encode strips EXIF/ICC; no metadata survives the upload path.
        assert not img.info.get("exif")
        assert not img.info.get("icc_profile")
    finally:
        app.dependency_overrides.clear()
        await client.aclose()
        await engine.dispose()
