"""Hydra Auth v1: pbkdf2-пароли, сессии, rate-limit, CLI-токен."""
import hashlib
import hmac
import os
import secrets
from datetime import datetime, timedelta

PBKDF2_ITERATIONS = 600_000

AUTH_SCHEMA = """
CREATE TABLE IF NOT EXISTS admin_credentials (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    password_hash TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
    token_hash TEXT PRIMARY KEY,
    user_agent TEXT,
    ip TEXT,
    remember INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS login_attempts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ip TEXT NOT NULL,
    success INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    endpoint TEXT NOT NULL DEFAULT 'login'
);
CREATE TABLE IF NOT EXISTS cli_tokens (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    token_hash TEXT NOT NULL,
    created_at TEXT NOT NULL
);
"""


def _now() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def hash_password(password: str) -> str:
    salt = os.urandom(16)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, PBKDF2_ITERATIONS)
    return f"pbkdf2_sha256${PBKDF2_ITERATIONS}${salt.hex()}${dk.hex()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        _, iters, salt_hex, dk_hex = stored.split("$")
        dk = hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt_hex), int(iters))
        return hmac.compare_digest(dk.hex(), dk_hex)
    except Exception:
        return False


def new_token() -> str:
    return secrets.token_urlsafe(32)


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


async def ensure_tables(db) -> None:
    for stmt in AUTH_SCHEMA.strip().split(";"):
        stmt = stmt.strip()
        if stmt:
            await db.execute(stmt)
    await db.commit()


async def has_admin(db) -> bool:
    return await db.fetchone("SELECT id FROM admin_credentials WHERE id = 1") is not None


async def set_admin_password(db, password: str) -> None:
    await db.execute(
        "INSERT OR REPLACE INTO admin_credentials (id, password_hash, created_at) VALUES (1, ?, ?)",
        hash_password(password), _now(),
    )
    await db.commit()


async def check_admin_password(db, password: str) -> bool:
    row = await db.fetchone("SELECT password_hash FROM admin_credentials WHERE id = 1")
    return bool(row) and verify_password(password, row["password_hash"])


async def create_session(db, remember: bool, user_agent: str, ip: str) -> str:
    token = new_token()
    hours = 24 * 30 if remember else 12
    expires = (datetime.now() + timedelta(hours=hours)).strftime("%Y-%m-%d %H:%M:%S")
    await db.execute(
        """INSERT INTO sessions (token_hash, user_agent, ip, remember, created_at, expires_at, last_seen_at)
           VALUES (?, ?, ?, ?, ?, ?, ?)""",
        token_hash(token), user_agent, ip, 1 if remember else 0, _now(), expires, _now(),
    )
    await db.commit()
    return token


async def get_session(db, token: str):
    if not token:
        return None
    th = token_hash(token)
    row = await db.fetchone("SELECT * FROM sessions WHERE token_hash = ? AND expires_at > ?", th, _now())
    if row:
        await db.execute("UPDATE sessions SET last_seen_at = ? WHERE token_hash = ?", _now(), th)
        await db.commit()
    return row


async def delete_session(db, token_hash_hex: str) -> None:
    await db.execute("DELETE FROM sessions WHERE token_hash = ?", token_hash_hex)
    await db.commit()


async def delete_all_sessions(db) -> None:
    await db.execute("DELETE FROM sessions")
    await db.commit()


async def list_sessions(db):
    return await db.fetchall("SELECT * FROM sessions ORDER BY last_seen_at DESC")


async def record_attempt(db, ip: str, success: bool, endpoint: str = "login") -> None:
    """Record auth attempt. endpoint: 'login' (default) or 'redeem' (Р-27)."""
    await db.execute(
        "INSERT INTO login_attempts (ip, success, created_at, endpoint) VALUES (?, ?, ?, ?)",
        ip, 1 if success else 0, _now(), endpoint,
    )
    await db.commit()


async def is_rate_limited(db, ip: str, endpoint: str = "login", max_attempts: int = 5) -> bool:
    """Check if IP is rate-limited. endpoint: 'login' (default) or 'redeem' (Р-27)."""
    cutoff = (datetime.now() - timedelta(minutes=15)).strftime("%Y-%m-%d %H:%M:%S")
    row = await db.fetchone(
        "SELECT COUNT(*) AS c FROM login_attempts WHERE ip = ? AND success = 0 AND created_at > ? AND endpoint = ?",
        ip, cutoff, endpoint,
    )
    return bool(row) and row["c"] >= max_attempts


async def get_or_create_cli_token(db):
    """Возвращает plaintext только при первом создании (показываем один раз)."""
    if await db.fetchone("SELECT id FROM cli_tokens WHERE id = 1"):
        return None
    token = new_token()
    await db.execute(
        "INSERT OR REPLACE INTO cli_tokens (id, token_hash, created_at) VALUES (1, ?, ?)",
        token_hash(token), _now(),
    )
    await db.commit()
    return token


async def rotate_cli_token(db) -> str:
    token = new_token()
    await db.execute(
        "INSERT OR REPLACE INTO cli_tokens (id, token_hash, created_at) VALUES (1, ?, ?)",
        token_hash(token), _now(),
    )
    await db.commit()
    return token


async def check_cli_token(db, token: str) -> bool:
    row = await db.fetchone("SELECT token_hash FROM cli_tokens WHERE id = 1")
    return bool(row) and hmac.compare_digest(row["token_hash"], token_hash(token))
