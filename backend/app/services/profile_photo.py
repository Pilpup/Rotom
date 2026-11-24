"""Owner-only profile photo processing.

Design goals: fixed 1:1 output, tiny stored bytes, no original retained,
no metadata leaked, and no decompression-bomb / polyglot surprises.
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import io
from dataclasses import dataclass
from typing import Optional

from PIL import Image, ImageOps, UnidentifiedImageError

# HEIC/HEIF (iPhone photos) decode server-side, never on the phone. Keeping
# this out of the client means old devices don't need a HEIF decoder.
try:
    import pillow_heif

    pillow_heif.register_heif_opener()
except ImportError:  # pragma: no cover - requirements pins the dependency
    pillow_heif = None

# Wire cap: 2 MB of decoded image bytes. Base64 text is ~33% larger, capped
# separately by the request schema before this function is called.
MAX_UPLOAD_BYTES = 2 * 1024 * 1024
# Refuse absurd source dimensions before a full decode (decompression bomb).
# 4000px is far above anything useful for a 256px avatar and bounds the
# decoded bitmap to ~64 MB before the center crop. Checked on the image
# header, so Pillow's global bomb threshold never needs mutating (which would
# race across concurrent thread-pool uploads).
MAX_SOURCE_DIMENSION = 4000
# Output is a 256x256 baseline JPEG - ~10-30 KB.
AVATAR_SIZE = 256
MAX_STORED_BYTES = 128 * 1024
JPEG_QUALITY = 82


class ProfilePhotoError(ValueError):
    """Client-safe processing failure with an HTTP status."""

    def __init__(self, detail: str, status_code: int = 400):
        super().__init__(detail)
        self.detail = detail
        self.status_code = status_code


@dataclass(frozen=True)
class ProcessedPhoto:
    data: bytes
    mime: str
    etag: str
    color_primary: str
    color_secondary: str
    color_tertiary: str


def _detect_kind(raw: bytes) -> Optional[str]:
    """Magic-byte sniffing. Content type claimed by the client is ignored."""
    if raw.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if raw.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if len(raw) >= 12 and raw[:4] == b"RIFF" and raw[8:12] == b"WEBP":
        return "image/webp"
    # ISO BMFF brands used by HEIC/HEIF (and AVIF, which libheif also reads).
    if len(raw) >= 12 and raw[4:8] == b"ftyp":
        brand = raw[8:12]
        if brand in (b"heic", b"heix", b"hevc", b"hevx", b"mif1", b"msf1", b"avif", b"avis"):
            return "image/heic"
    return None


def _decode_base64(value: str) -> bytes:
    # validate=True rejects embedded whitespace and non-alphabet junk.
    try:
        raw = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError):
        raise ProfilePhotoError("Image payload is not valid base64")
    if not raw:
        raise ProfilePhotoError("Image payload is empty")
    if len(raw) > MAX_UPLOAD_BYTES:
        raise ProfilePhotoError("Image is too large (2 MB limit)", 413)
    if _detect_kind(raw) is None:
        # SVG, GIF, APNG, executables and polyglots all die here.
        raise ProfilePhotoError("Only JPEG, PNG, WebP and HEIC images are accepted")
    return raw


def _extract_palette(image: Image.Image) -> tuple[str, str, str]:
    """Pick three distinct, vibrant-enough colors from a tiny thumbnail.

    Plain frequency ranking picks the dark background or near-white areas of
    most photos, which makes the background mesh muddy. Score buckets by how
    often they occur AND by saturation/brightness instead.
    """
    import colorsys

    small = image.resize((24, 24), Image.Resampling.BILINEAR).convert("RGB")
    buckets: dict[tuple[int, int, int], list[tuple[int, int, int]]] = {}
    for r, g, b in small.get_flattened_data():
        key = (r >> 4, g >> 4, b >> 4)
        buckets.setdefault(key, []).append((r, g, b))

    ranked: list[tuple[float, tuple[int, int, int]]] = []
    for values in buckets.values():
        avg = tuple(sum(c[i] for c in values) // len(values) for i in range(3))
        hue, saturation, value = colorsys.rgb_to_hsv(*(c / 255 for c in avg))
        # Heavy penalty for near-black and washed-out near-white buckets.
        score = len(values) * (0.30 + saturation * 1.7) * (0.35 + value * 0.9)
        ranked.append((score, avg))  # type: ignore[arg-type]
    ranked.sort(key=lambda item: -item[0])

    chosen: list[tuple[int, int, int]] = []
    for _, avg in ranked:
        if max(avg) < 40:  # too dark to be useful as a glow
            continue
        if any(sum(abs(a - b) for a, b in zip(avg, other)) < 70 for other in chosen):
            continue
        chosen.append(avg)
        if len(chosen) == 3:
            break

    # Fall back to bright theme colors if a photo is nearly monochrome.
    defaults = [(124, 58, 237), (255, 122, 0), (59, 130, 246)]
    for color in defaults:
        if len(chosen) >= 3:
            break
        if all(sum(abs(a - b) for a, b in zip(color, other)) >= 70 for other in chosen):
            chosen.append(color)

    return tuple("#%02X%02X%02X" % color for color in chosen[:3])  # type: ignore[return-value]


def process_profile_photo(image_base64: str) -> ProcessedPhoto:
    raw = _decode_base64(image_base64)
    try:
        # verify() walks the file structure without allocating the full bitmap.
        with Image.open(io.BytesIO(raw)) as probe:
            if probe.width < 1 or probe.height < 1:
                raise ProfilePhotoError("Image has invalid dimensions")
            if probe.width > MAX_SOURCE_DIMENSION or probe.height > MAX_SOURCE_DIMENSION:
                raise ProfilePhotoError("Image dimensions are too large", 413)
            probe.verify()
    except ProfilePhotoError:
        raise
    except (UnidentifiedImageError, OSError, ValueError):
        raise ProfilePhotoError("Image file is corrupt")

    try:
        with Image.open(io.BytesIO(raw)) as opened:
            opened.load()
            # Honor camera rotation before cropping, then drop all metadata.
            image = ImageOps.exif_transpose(opened)
            side = min(image.width, image.height)
            left = (image.width - side) // 2
            top = (image.height - side) // 2
            image = image.crop((left, top, left + side, top + side))
            image = image.resize((AVATAR_SIZE, AVATAR_SIZE), Image.Resampling.LANCZOS)

            # JPEG has no alpha: composite over white instead of leaving a
            # transparent PNG to become black on decode.
            rgba = image.convert("RGBA")
            rgb = Image.new("RGB", (AVATAR_SIZE, AVATAR_SIZE), (255, 255, 255))
            rgb.paste(rgba, mask=rgba.getchannel("A"))
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError):
        raise ProfilePhotoError("Image could not be processed")

    primary, secondary, tertiary = _extract_palette(rgb)
    out = io.BytesIO()
    # Baseline (non-progressive) JPEG decodes faster on old phones.
    rgb.save(out, format="JPEG", quality=JPEG_QUALITY, optimize=True, progressive=False)
    data = out.getvalue()
    if len(data) > MAX_STORED_BYTES:
        raise ProfilePhotoError("Processed image is too large", 413)

    return ProcessedPhoto(
        data=data,
        mime="image/jpeg",
        etag=hashlib.sha256(data).hexdigest()[:16],
        color_primary=primary,
        color_secondary=secondary,
        color_tertiary=tertiary,
    )
