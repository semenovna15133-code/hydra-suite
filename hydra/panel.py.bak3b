from fastapi import FastAPI, HTTPException, Header, Request
from fastapi.templating import Jinja2Templates
from fastapi.staticfiles import StaticFiles
from fastapi.responses import HTMLResponse, JSONResponse
import os
from datetime import datetime
from fastapi import FastAPI, HTTPException, Header, Request
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel
from typing import Optional
from .core.db import Database
from .core.server_manager import ServerManager

DB_PATH = os.environ.get("HYDRA_DB_PATH", "panel.db")


# Pydantic models
class ServerRegister(BaseModel):
    server_id: str
    ip: str
    location: str = ""
    city: str = ""
    bandwidth_mbps: int = 1000
    ssh_port: int = 22


class ClientCreate(BaseModel):
    label: str
    days: int = 0
    profile: str = "balanced"


class RedeemRequest(BaseModel):
    key_id: str
    device_id: str
    device_name: str


class AgentMetrics(BaseModel):
    server_id: str
    timestamp: str
    cpu_percent: float
    memory_percent: float
    network_rx_mbps: float
    network_tx_mbps: float
    connections_wdtt: int
    connections_aivpn: int
    connections_awg: int
    status_wdtt: str
    status_aivpn: str
    status_awg: str
    latency_ms: float


# Application setup
app = FastAPI(
    title="Hydra Control Panel",
    description="Multi-protocol VPN server management",
    version="0.1.0",
)

# Setup templates and static files
templates = Jinja2Templates(directory="templates")
app.mount("/static", StaticFiles(directory="static"), name="static")


# Global state
db: Optional[Database] = None
manager: Optional[ServerManager] = None


@app.on_event("startup")
async def startup():
    """Initialize database and manager on startup."""
    global db, manager
    
    # Пути настраиваются через переменные окружения
    db_path = os.environ.get("HYDRA_DB_PATH", DB_PATH)
    ssh_key = os.environ.get("HYDRA_SSH_KEY", "/opt/hydra/keys/panel_key")
    
    db = Database(db_path)
    await db.connect()
    await db.init_schema()
    manager = ServerManager(db, ssh_key)


@app.on_event("shutdown")
async def shutdown():
    """Close database on shutdown."""
    if db:
        await db.close()


# Health check
@app.get("/health")
async def health():
    """Simple health check."""
    return {"status": "ok", "service": "hydra-panel"}


# Server endpoints
@app.post("/api/v1/servers")
async def register_server(data: ServerRegister):
    """Register a new server."""
    try:
        server = await manager.register_server(
            server_id=data.server_id,
            ip=data.ip,
            location=data.location,
            city=data.city,
            bandwidth_mbps=data.bandwidth_mbps,
            ssh_port=data.ssh_port,
        )
        return {"status": "created", "server": server}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@app.get("/api/v1/servers")
async def list_servers():
    """List all registered servers."""
    servers = await db.fetchall("SELECT * FROM servers ORDER BY created_at DESC")
    return {"servers": servers}


@app.get("/api/v1/servers/{server_id}")
async def get_server(server_id: str):
    """Get server details."""
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        raise HTTPException(status_code=404, detail="Server not found")
    return server


@app.post("/api/v1/servers/{server_id}/install")
async def install_protocols(server_id: str):
    """Install all protocols on server."""
    try:
        results = await manager.install_all_protocols(server_id)
        return {"status": "completed", "results": results}
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/v1/servers/{server_id}/status")
async def get_server_status(server_id: str):
    """Get aggregated status for all protocols."""
    try:
        status = await manager.get_server_status(server_id)
        return status
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# Client endpoints
@app.post("/api/v1/servers/{server_id}/clients/{protocol}")
async def add_client(server_id: str, protocol: str, data: ClientCreate):
    """Add client to specific protocol on server."""
    try:
        result = await manager.add_client_to_server(
            server_id=server_id,
            protocol=protocol,
            label=data.label,
            days=data.days,
            profile=data.profile,
        )
        return {"status": "created", "client": result}
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/v1/servers/{server_id}/clients/{protocol}")
async def list_clients(server_id: str, protocol: str):
    """List all clients for protocol on server."""
    try:
        clients = await manager.list_clients_on_server(server_id, protocol)
        return {"clients": clients}
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.delete("/api/v1/servers/{server_id}/clients/{protocol}/{client_id}")
async def remove_client(server_id: str, protocol: str, client_id: str):
    """Remove client from protocol on server."""
    try:
        result = await manager.remove_client_from_server(
            server_id=server_id,
            protocol=protocol,
            client_id=client_id,
        )
        return result
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# Agent endpoints
@app.post("/api/v1/agent/metrics")
async def agent_metrics(
    metrics: AgentMetrics,
    authorization: Optional[str] = Header(None),
):
    """Receive metrics from agent.
    
    If server has a pending rotation (needs_new_token flag), returns
    X-New-Token header. Agent picks it up and updates config.env.
    """
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing or invalid token")
    
    token = authorization.split(" ", 1)[1]
    
    valid = await manager.verify_agent_token(metrics.server_id, token)
    if not valid:
        raise HTTPException(status_code=401, detail="Invalid token")
    
    await db.execute(
        """INSERT INTO metrics 
           (server_id, timestamp, cpu_percent, memory_percent, 
            network_rx_mbps, network_tx_mbps,
            connections_wdtt, connections_aivpn, connections_awg,
            status_wdtt, status_aivpn, status_awg, latency_ms)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        metrics.server_id, metrics.timestamp,
        metrics.cpu_percent, metrics.memory_percent,
        metrics.network_rx_mbps, metrics.network_tx_mbps,
        metrics.connections_wdtt, metrics.connections_aivpn, metrics.connections_awg,
        metrics.status_wdtt, metrics.status_aivpn, metrics.status_awg,
        metrics.latency_ms,
    )
    await db.commit()
    
    # Проверить нужна ли ротация (token истекает через < 7 дней)
    response = {"status": "accepted"}
    headers = {}
    
    needs_rotation = await db.scalar(
        """SELECT COUNT(*) FROM agent_tokens
           WHERE server_id = ?
             AND expires_at > datetime('now')
             AND expires_at < datetime('now', '+7 days')""",
        metrics.server_id,
    )
    
    if needs_rotation and needs_rotation > 0:
        # Только если это самый свежий токен (по created_at)
        latest = await db.fetchone(
            """SELECT token_hash, expires_at FROM agent_tokens
               WHERE server_id = ? AND expires_at > datetime('now')
               ORDER BY created_at DESC LIMIT 1""",
            metrics.server_id,
        )
        
        # Если текущий токен истекает скоро и он самый свежий — выдать новый
        import hashlib
        current_hash = hashlib.sha256(token.encode()).hexdigest()
        if latest and latest['token_hash'] == current_hash:
            from .core.token_rotation import rotate_agent_token
            new_token = await rotate_agent_token(db, metrics.server_id)
            headers["X-New-Token"] = new_token
    
    from fastapi.responses import JSONResponse, HTMLResponse
    return JSONResponse(content=response, headers=headers)


# Client redeem endpoint (real implementation below, Р-27 rate-limit applied)
# Stub removed: was conflicting with real implementation at line 330


@app.get("/api/v1/client/servers")
async def get_client_servers(key_id: str):
    """Get list of servers for a key."""
    return {"status": "not_implemented", "key_id": key_id}


# Bot endpoints
@app.post("/api/v1/bot/heartbeat")
async def bot_heartbeat(
    bot_id: str,
    uptime: int,
    last_update_id: int = 0,
    pending_updates: int = 0,
):
    """Receive heartbeat from Telegram bot."""
    await db.execute(
        """INSERT OR REPLACE INTO bot_heartbeats 
           (bot_id, last_heartbeat_at, last_update_id, pending_updates, uptime_sec)
           VALUES (?, datetime('now'), ?, ?, ?)""",
        bot_id, last_update_id, pending_updates, uptime,
    )
    await db.commit()
    return {"status": "ok"}


@app.get("/api/v1/bot/token-check")
async def bot_token_check():
    """Check bot token validity (called by panel itself)."""
    return {"ok": True}


# Redeem endpoint (full implementation)
from .core.redeem import (
    redeem_key as redeem_key_logic,
    DeviceLimitReached,
    KeyNotFound,
    KeyRevoked,
    KeyExpired,
)
from fastapi import Request


@app.post("/api/v1/client/redeem")
async def redeem_key(data: RedeemRequest, request: Request):
    """Redeem universal key and get configs for all servers.
    
    Implements:
    - Idempotency: same device_id returns existing config
    - Device limit check (max_devices, default 3)
    - Race protection via BEGIN IMMEDIATE
    - Subnet collision detection (alert if 2+ subnets in 5 min)
    
    IP is read from request.remote_addr (reverse proxy forbidden).
    
    Security (Р-27): endpoint is public (client redeems from any network),
    but rate-limited to prevent brute-force attacks on key_id.
    """
    # Get client IP from request (not X-Forwarded-For)
    ip = request.client.host if request.client else "0.0.0.0"
    
    # Security (Р-27): rate-limit per IP (10 attempts per 15 min for redeem)
    from .core import auth as auth_module
    is_limited = await auth_module.is_rate_limited(db, ip, endpoint="redeem", max_attempts=10)
    if is_limited:
        raise HTTPException(
            status_code=429,
            detail="Too many redeem attempts. Try again in 15 minutes."
        )
    
    try:
        result = await redeem_key_logic(
            db=db,
            key_id=data.key_id,
            device_id=data.device_id,
            device_name=data.device_name,
            ip=ip,
        )
        return result
        
    except KeyNotFound:
        raise HTTPException(status_code=404, detail="Key not found")
    except KeyRevoked:
        raise HTTPException(status_code=403, detail="Key is revoked")
    except KeyExpired:
        raise HTTPException(status_code=403, detail="Key is expired")
    except DeviceLimitReached as e:
        raise HTTPException(status_code=403, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Redeem failed: {str(e)}")


# Token rotation endpoints
@app.post("/api/v1/servers/{server_id}/tokens/rotate")
async def rotate_token(server_id: str):
    """Manually rotate agent token for server."""
    from .core.token_rotation import rotate_agent_token
    
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        raise HTTPException(status_code=404, detail="Server not found")
    
    try:
        new_token = await rotate_agent_token(db, server_id)
        return {
            "status": "rotated",
            "server_id": server_id,
            "note": "Deliver this token to agent via X-New-Token or manual update",
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/v1/servers/{server_id}/tokens/revoke")
async def revoke_tokens(server_id: str):
    """Immediately revoke all tokens (compromise scenario)."""
    from .core.token_rotation import manual_revoke_all
    
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        raise HTTPException(status_code=404, detail="Server not found")
    
    count = await manual_revoke_all(db, server_id)
    return {"status": "revoked", "tokens_affected": count}


@app.get("/api/v1/servers/{server_id}/tokens")
async def list_tokens(server_id: str):
    """List token status for server (diagnostics)."""
    from .core.token_rotation import get_active_token_count
    
    tokens = await db.fetchall(
        """SELECT token_hash, expires_at, created_at, rotated_at
           FROM agent_tokens
           WHERE server_id = ?
           ORDER BY created_at DESC""",
        server_id,
    )
    
    # Не возвращаем полные хеши — только первые 8 символов для диагностики
    safe_tokens = [
        {
            "token_hash_prefix": t["token_hash"][:8],
            "expires_at": t["expires_at"],
            "created_at": t["created_at"],
            "rotated_at": t["rotated_at"],
            "active": t["expires_at"] > datetime.utcnow().isoformat() if t["expires_at"] else False,
        }
        for t in tokens
    ]
    
    active_count = await get_active_token_count(db, server_id)
    
    return {
        "server_id": server_id,
        "active_count": active_count,
        "tokens": safe_tokens,
    }


# Forecast and reports endpoints
@app.get("/api/v1/forecast/{server_id}")
async def get_forecast(server_id: str, days: int = 7):
    """Get load forecast for specific server."""
    from .core.forecast import analyze_server_load
    
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        raise HTTPException(status_code=404, detail="Server not found")
    
    forecast = await analyze_server_load(db, server_id, days)
    return forecast


@app.get("/api/v1/forecast")
async def get_all_forecasts():
    """Get load forecasts for all servers."""
    from .core.forecast import forecast_all_servers
    
    forecasts = await forecast_all_servers(db)
    return {"forecasts": forecasts}


@app.post("/api/v1/reports/weekly/generate")
async def generate_weekly_report_endpoint(
    week_start: Optional[str] = None,
    week_end: Optional[str] = None,
):
    """Manually generate weekly report."""
    from .core.weekly_report import generate_weekly_report
    
    if week_start and week_end:
        start = datetime.fromisoformat(week_start)
        end = datetime.fromisoformat(week_end)
    else:
        # По умолчанию — последняя полная неделя
        now = datetime.utcnow()
        days_since_monday = now.weekday()
        end = now - timedelta(days=days_since_monday)
        start = end - timedelta(days=7)
    
    report = await generate_weekly_report(db, start, end)
    return report


@app.get("/api/v1/reports/weekly")
async def list_weekly_reports(limit: int = 10):
    """List weekly reports."""
    reports = await db.fetchall(
        """SELECT week_start, week_end, generated_at
           FROM weekly_reports
           ORDER BY week_start DESC
           LIMIT ?""",
        limit,
    )
    return {"reports": reports}


@app.get("/api/v1/reports/weekly/{week_start}")
async def get_weekly_report(week_start: str):
    """Get specific weekly report."""
    report = await db.fetchone(
        "SELECT * FROM weekly_reports WHERE week_start = ?",
        week_start,
    )
    if not report:
        raise HTTPException(status_code=404, detail="Report not found")
    return report




# Web UI Routes
@app.get("/", response_class=HTMLResponse)
async def dashboard(request: Request):
    """Dashboard page."""
    # Get stats
    stats = {
        "total_servers": await db.scalar("SELECT COUNT(*) FROM servers") or 0,
        "total_clients": await db.scalar("SELECT COUNT(*) FROM key_server_clients") or 0,
        "total_keys": await db.scalar("SELECT COUNT(*) FROM access_keys") or 0,
        "active_alerts": await db.scalar("SELECT COUNT(*) FROM alerts WHERE resolved_at IS NULL") or 0,
    }
    
    # Get recent servers
    servers = await db.fetchall("SELECT * FROM servers ORDER BY created_at DESC LIMIT 5")
    
    return templates.TemplateResponse(
        request=request,
        name="pages/dashboard.html",
        context={"stats": stats, "servers": servers}
    )


@app.get("/servers", response_class=HTMLResponse)
async def servers_list(request: Request, error: Optional[str] = None, deleted: Optional[str] = None, msg: Optional[str] = None):
    """Servers list page."""
    servers = await db.fetchall("SELECT * FROM servers ORDER BY created_at DESC")
    return templates.TemplateResponse(
        request=request,
        name="pages/servers.html",
        context={"servers": servers, "error": error, "deleted": deleted, "msg": msg}
    )


@app.get("/servers/{server_id}", response_class=HTMLResponse)
async def server_detail(request: Request, server_id: str, msg: Optional[str] = None):
    """Server detail page."""
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        raise HTTPException(status_code=404, detail="Server not found")
    
    # Get protocol instances
    protocols = await db.fetchall(
        "SELECT * FROM protocol_instances WHERE server_id = ?",
        server_id
    )
    
    # Get recent metrics
    metrics = await db.fetchall(
        """SELECT * FROM metrics 
           WHERE server_id = ? 
           ORDER BY timestamp DESC 
           LIMIT 100""",
        server_id
    )
    
    return templates.TemplateResponse(
        request=request,
        name="pages/server_detail.html",
        context={"server": server, "protocols": protocols, "metrics": metrics, "msg": msg}
    )



@app.get("/reports", response_class=HTMLResponse)
async def reports_list(request: Request):
    """Reports list page."""
    reports = await db.fetchall(
        "SELECT * FROM weekly_reports ORDER BY week_start DESC LIMIT 20"
    )
    return templates.TemplateResponse(
        request=request,
        name="pages/reports.html",
        context={"reports": reports}
    )

@app.post("/api/v1/reports/weekly/catch-up")
async def catch_up_reports():
    """Generate all missed weekly reports."""
    from .core.weekly_report import check_and_generate_missed_reports
    
    generated = await check_and_generate_missed_reports(db)
    return {
        "status": "completed",
        "reports_generated": len(generated),
        "details": generated,
    }


# Server creation from web form
from fastapi import Form
from fastapi.responses import RedirectResponse


@app.post("/servers/create")
async def create_server_form(
    request: Request,
    server_id: str = Form(...),
    ip: str = Form(...),
    location: str = Form(""),
    city: str = Form(""),
    bandwidth_mbps: int = Form(1000),
    ssh_port: int = Form(22),
    ssh_password: str = Form(""),
):
    """Create server from web form (server-side rendering)."""
    from urllib.parse import quote
    try:
        await manager.register_server(
            server_id=server_id,
            ip=ip,
            location=location,
            city=city,
            bandwidth_mbps=bandwidth_mbps,
            ssh_port=ssh_port,
        )
    except Exception as e:
        return RedirectResponse("/servers?error=" + quote(str(e)), status_code=303)

    await db.execute("UPDATE servers SET status = 'unknown' WHERE id = ?", server_id)
    await db.commit()

    # Onboarding v1: одноразовый пароль -> установка ключа панели
    msg = "server_added"
    if ssh_password:
        pub = _read_panel_pubkey()
        if not pub:
            msg = quote("Сервер добавлен, но нет публичного ключа панели (keys/hydra_key.pub)")
        else:
            try:
                code, out, err = await _ssh_run_once(
                    ip, ssh_port, manager.ssh_key_path,
                    "mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys "
                    "&& chmod 600 ~/.ssh/authorized_keys && "
                    f"(grep -qF '{pub}' ~/.ssh/authorized_keys || echo '{pub}' >> ~/.ssh/authorized_keys)",
                    password=ssh_password,
                )
                if code == 0:
                    msg = "bootstrap_ok"
                    await db.execute("UPDATE servers SET status = 'active' WHERE id = ?", server_id)
                    await db.commit()
                else:
                    msg = quote("Сервер добавлен, бутстрап ошибся: " + (err or out)[:150])
                    await db.execute("UPDATE servers SET status = 'offline' WHERE id = ?", server_id)
                    await db.commit()
            except TimeoutError:
                msg = quote("Сервер добавлен, SSH с паролем: таймаут 8 сек")
                await db.execute("UPDATE servers SET status = 'offline' WHERE id = ?", server_id)
                await db.commit()
            except Exception as e:
                msg = quote("Сервер добавлен, SSH с паролем недоступен: " + str(e)[:150])
                await db.execute("UPDATE servers SET status = 'offline' WHERE id = ?", server_id)
                await db.commit()
    return RedirectResponse(f"/servers?msg={msg}", status_code=303)

@app.post("/servers/{server_id}/delete")
async def delete_server_form(server_id: str):
    """Delete server from web form (cascades to related data)."""
    from urllib.parse import quote
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        return RedirectResponse("/servers?error=Server+not+found", status_code=303)
    
    # Каскадное удаление: protocol_instances, metrics, alerts,
    # agent_tokens, key_server_clients (по FOREIGN KEY ... ON DELETE CASCADE)
    await db.execute("DELETE FROM servers WHERE id = ?", server_id)
    await db.commit()
    
    return RedirectResponse(f"/servers?deleted={quote(server_id)}", status_code=303)


# === Управление ключами доступа (UI) ===
from fastapi import Form
from datetime import datetime, timedelta
import secrets
import aiosqlite

@app.post("/api/v1/keys/create")
async def create_key_ui(days_valid: int = Form(30), max_devices: int = Form(3)):
    """Создать новый ключ доступа из UI"""
    key_id = secrets.token_urlsafe(32)
    expires_at = (datetime.utcnow() + timedelta(days=days_valid)).isoformat()
    
    if db is None:
        raise HTTPException(status_code=503, detail="Database not initialized")
    await db.execute(
        "INSERT INTO access_keys (key_id, expires_at, max_devices) VALUES (?, ?, ?)",
        key_id, expires_at, max_devices,
    )
    await db.commit()
    
    from fastapi.responses import RedirectResponse
    return RedirectResponse(url="/keys", status_code=303)


@app.post("/api/v1/keys/{key_id}/revoke")
async def revoke_key_ui(key_id: str):
    """Отозвать ключ доступа из UI"""
    revoked_at = datetime.utcnow().isoformat()
    
    if db is None:
        raise HTTPException(status_code=503, detail="Database not initialized")
    await db.execute(
        "UPDATE access_keys SET revoked_at = ? WHERE key_id = ?",
        revoked_at, key_id,
    )
    await db.commit()
    
    from fastapi.responses import RedirectResponse
    return RedirectResponse(url="/keys", status_code=303)


@app.post("/servers/{server_id}/install-protocols")
async def install_protocols_form(server_id: str):
    """Install all protocols from web UI (idempotent)."""
    from urllib.parse import quote
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        return RedirectResponse("/servers?error=Server+not+found", status_code=303)
    try:
        results = await manager.install_all_protocols(server_id)
        res = results.get("results", {})
        ok = all(r.get("success") for r in res.values())
        msg = "protocols_ok" if ok else "protocols_partial"
    except Exception as e:
        msg = quote("Ошибка установки: " + str(e))
    return RedirectResponse(f"/servers/{server_id}?msg={msg}", status_code=303)


@app.post("/servers/{server_id}/install-agent")
async def install_agent_form(request: Request, server_id: str):
    """Install monitoring agent from web UI (idempotent)."""
    from urllib.parse import quote
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        return RedirectResponse("/servers?error=Server+not+found", status_code=303)
    try:
        panel_url = str(request.base_url).rstrip("/")
        result = await manager.install_agent(server_id=server_id, panel_url=panel_url)
        status = result.get("status", "unknown")
        if status in ("success", "already_installed"):
            # Синхронизируем флаг с реальным состоянием на сервере
            await db.execute("UPDATE servers SET agent_installed = 1 WHERE id = ?", server_id)
            await db.commit()
            # Взятие под управление: выставляем часовой пояс панели
            try:
                async with SSHTransport(server["ip"], key_path=manager.ssh_key_path) as ssh:
                    await ssh.run(f"timedatectl set-timezone {PANEL_TIMEZONE}")
            except Exception:
                pass
        msg = {"success": "agent_ok", "already_installed": "agent_exists"}.get(status, quote("Агент: " + status))
    except Exception as e:
        msg = quote("Ошибка агента: " + str(e))
    return RedirectResponse(f"/servers/{server_id}?msg={msg}", status_code=303)


# === Key management (web UI) ===

@app.post("/keys/{key_id}/revoke")
async def revoke_key_form(key_id: str):
    """Приостановить ключ (redeem и новые подключения отклоняются)."""
    key = await db.fetchone("SELECT key_id FROM access_keys WHERE key_id = ?", key_id)
    if not key:
        return RedirectResponse("/keys?msg=key_not_found", status_code=303)
    await db.execute(
        "UPDATE access_keys SET revoked_at = datetime('now') WHERE key_id = ?",
        key_id,
    )
    await db.commit()
    return RedirectResponse("/keys?msg=key_revoked", status_code=303)


@app.post("/keys/{key_id}/restore")
async def restore_key_form(key_id: str):
    """Возобновить действие ключа."""
    await db.execute(
        "UPDATE access_keys SET revoked_at = NULL WHERE key_id = ?",
        key_id,
    )
    await db.commit()
    return RedirectResponse("/keys?msg=key_restored", status_code=303)


@app.post("/keys/{key_id}/delete")
async def delete_key_form(key_id: str):
    """Удалить ключ каскадно со всеми привязками."""
    await db.execute("DELETE FROM device_connections WHERE key_id = ?", key_id)
    await db.execute("DELETE FROM device_registrations WHERE key_id = ?", key_id)
    await db.execute("DELETE FROM key_server_clients WHERE key_id = ?", key_id)
    await db.execute("DELETE FROM access_keys WHERE key_id = ?", key_id)
    await db.commit()
    return RedirectResponse("/keys?msg=key_deleted", status_code=303)


@app.get("/keys/{key_id}/download")
async def download_key_conf(request: Request, key_id: str):
    """Скачать ключ в формате Hydra Key File v1 (.conf)."""
    import hashlib
    key = await db.fetchone("SELECT * FROM access_keys WHERE key_id = ?", key_id)
    if not key:
        raise HTTPException(status_code=404, detail="Key not found")
    
    panel_url = str(request.base_url).rstrip("/")
    checksum = hashlib.sha256(f"{key_id}{panel_url}".encode()).hexdigest()
    expires = (key["expires_at"] or "never").replace("T", " ")
    
    content = f"""# Hydra Key File v1
# Сгенерировано Hydra Panel: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}
# Формат: docs/KEYFILE.md (INI-совместимый)

[hydra]
version = 1
type = access-key
key = {key_id}
panel_url = {panel_url}
expires_at = {expires}
max_devices = {key['max_devices']}
checksum_sha256 = {checksum}
"""
    from fastapi.responses import Response
    filename = f"hydra-key-{key_id[:8]}.conf"
    return Response(
        content=content,
        media_type="text/plain",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


# === Client (device) management (web UI) ===



@app.post("/servers/{server_id}/reboot")
async def reboot_server_form(server_id: str):
    """Перезагрузка VPS через SSH (с задержкой для корректного закрытия сессии)."""
    from urllib.parse import quote
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        return RedirectResponse("/servers?error=Server+not+found", status_code=303)
    try:
        async with SSHTransport(server["ip"], key_path=manager.ssh_key_path) as ssh:
            await ssh.run("nohup sh -c 'sleep 2 && reboot' >/dev/null 2>&1 &")
        msg = "reboot_started"
    except Exception as e:
        msg = quote("Ошибка перезагрузки: " + str(e))
    return RedirectResponse(f"/servers/{server_id}?msg={msg}", status_code=303)


@app.post("/servers/{server_id}/restart-service")
async def restart_service_form(server_id: str, protocol: str = Form(...)):
    """Перезапуск сервиса протокола на сервере."""
    from urllib.parse import quote
    cmd = PROTOCOL_RESTART_CMDS.get(protocol)
    if not cmd:
        return RedirectResponse(f"/servers/{server_id}?msg=unknown_protocol", status_code=303)
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        return RedirectResponse("/servers?error=Server+not+found", status_code=303)
    try:
        async with SSHTransport(server["ip"], key_path=manager.ssh_key_path) as ssh:
            result = await ssh.run(cmd)
        if result.exit_code == 0:
            msg = f"service_restarted_{protocol}"
        else:
            msg = quote(f"Сервис ответил ошибкой: {(result.stderr or result.stdout)[:200]}")
    except Exception as e:
        msg = quote("SSH ошибка: " + str(e))
    return RedirectResponse(f"/servers/{server_id}?msg={msg}", status_code=303)


@app.post("/servers/{server_id}/rotate-token")
async def rotate_token_form(server_id: str):
    """Ротация токена агента (старый в grace-периоде, агент получит новый по X-New-Token)."""
    from urllib.parse import quote
    try:
        await manager.generate_agent_token(server_id)
        msg = "token_rotated"
    except Exception as e:
        msg = quote("Ошибка ротации: " + str(e))
    return RedirectResponse(f"/servers/{server_id}?msg={msg}", status_code=303)


@app.get("/servers/{server_id}/logs", response_class=HTMLResponse)
async def server_logs(request: Request, server_id: str, source: str = "agent"):
    """Страница логов сервера (SSH tail/journalctl)."""
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        raise HTTPException(status_code=404, detail="Server not found")
    
    cmd = PROTOCOL_LOG_CMDS.get(source, PROTOCOL_LOG_CMDS["agent"])
    logs = ""
    error = None
    try:
        async with SSHTransport(server["ip"], key_path=manager.ssh_key_path) as ssh:
            result = await ssh.run(cmd)
        logs = result.stdout or result.stderr or "(пусто)"
    except Exception as e:
        error = str(e)
    
    return templates.TemplateResponse(
        request=request,
        name="pages/server_logs.html",
        context={
            "server": server,
            "source": source,
            "logs": logs,
            "error": error,
            "sources": list(PROTOCOL_LOG_CMDS.keys()),
        },
    )


@app.post("/api/v1/agent/batch")
async def agent_batch(request: Request, authorization: str = Header(None)):
    """Приём пакета метрик от агента: JSON-массив или NDJSON."""
    import hashlib as _hashlib
    import json as _json
    from datetime import datetime as _dt

    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing token")
    token = authorization[7:]

    server = await db.fetchone(
        """SELECT s.id FROM servers s
           JOIN agent_tokens t ON t.server_id = s.id
           WHERE t.token_hash = ? AND (t.expires_at IS NULL OR t.expires_at > datetime('now'))""",
        _hashlib.sha256(token.encode()).hexdigest(),
    )
    if not server:
        raise HTTPException(status_code=401, detail="Invalid token")

    body = (await request.body()).decode("utf-8", errors="replace").strip()
    if not body:
        return {"status": "ok", "inserted": 0}

    items = []
    try:
        parsed = _json.loads(body)
        if isinstance(parsed, list):
            items = parsed
        elif isinstance(parsed, dict):
            items = [parsed]
    except Exception:
        # buffer.json = конкатенированные pretty JSON объекты
        decoder = _json.JSONDecoder()
        idx, n = 0, len(body)
        while idx < n:
            while idx < n and body[idx] in " \t\r\n":
                idx += 1
            if idx >= n:
                break
            try:
                obj, end_idx = decoder.raw_decode(body, idx)
                if isinstance(obj, dict):
                    items.append(obj)
                elif isinstance(obj, list):
                    items.extend(obj)
                idx = end_idx
            except ValueError:
                idx += 1

    inserted = 0
    for m in items:
        if not isinstance(m, dict):
            continue
        try:
            await db.execute(
                """INSERT OR IGNORE INTO metrics
                   (server_id, timestamp,
                    cpu_percent, memory_percent,
                    network_rx_mbps, network_tx_mbps,
                    connections_wdtt, connections_aivpn, connections_awg,
                    status_wdtt, status_aivpn, status_awg,
                    latency_ms)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                server["id"],
                m.get("timestamp") or _dt.now().strftime("%Y-%m-%d %H:%M:%S"),
                float(m.get("cpu_percent", 0) or 0),
                float(m.get("memory_percent", 0) or 0),
                float(m.get("network_rx_mbps", 0) or 0),
                float(m.get("network_tx_mbps", 0) or 0),
                int(m.get("connections_wdtt", 0) or 0),
                int(m.get("connections_aivpn", 0) or 0),
                int(m.get("connections_awg", 0) or 0),
                str(m.get("status_wdtt") or "unknown"),
                str(m.get("status_aivpn") or "unknown"),
                str(m.get("status_awg") or "unknown"),
                float(m.get("latency_ms", 0) or 0),
            )
            inserted += 1
        except Exception as e:
            continue

    await db.commit()
    return {"status": "ok", "inserted": inserted, "parsed": len(items)}
    inserted = 0
    for m in items:
        if not isinstance(m, dict):
            continue
        try:
            await db.execute(
                """INSERT OR IGNORE INTO metrics
                   (server_id, timestamp, cpu_percent, memory_percent,
                    network_rx_mbps, network_tx_mbps, latency_ms)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                server["id"],
                m.get("timestamp") or _dt.now().strftime("%Y-%m-%d %H:%M:%S"),
                float(m.get("cpu_percent", 0) or 0),
                float(m.get("memory_percent", 0) or 0),
                float(m.get("network_rx_mbps", 0) or 0),
                float(m.get("network_tx_mbps", 0) or 0),
                float(m.get("latency_ms", 0) or 0),
            )
            inserted += 1
        except Exception:
            continue

    await db.commit()
    return {"status": "ok", "inserted": inserted}


# === Timezone management ===

PANEL_TIMEZONE = "Europe/Moscow"


@app.post("/servers/{server_id}/sync-time")
async def sync_time_form(server_id: str):
    """Выставить часовой пояс панели на сервере."""
    from urllib.parse import quote
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        return RedirectResponse("/servers?error=Server+not+found", status_code=303)
    try:
        async with SSHTransport(server["ip"], key_path=manager.ssh_key_path) as ssh:
            result = await ssh.run(f"timedatectl set-timezone {PANEL_TIMEZONE} && date")
        if result.exit_code == 0:
            msg = "time_synced"
        else:
            msg = quote("Ошибка времени: " + (result.stderr or result.stdout)[:200])
    except Exception as e:
        msg = quote("SSH ошибка: " + str(e))
    return RedirectResponse(f"/servers/{server_id}?msg={msg}", status_code=303)


# === Protocol configs viewer (read-only) ===

CONFIG_DIRS = {
    "wdtt": "/etc/wdtt",
    "aivpn": "/etc/aivpn",
    "awg": "/etc/amnezia/amneziawg /etc/wireguard /etc/amnezia-wg",
}


@app.get("/servers/{server_id}/configs", response_class=HTMLResponse)
async def server_configs(request: Request, server_id: str, source: str = "wdtt"):
    """Просмотр конфигов протокола на сервере (read-only)."""
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        raise HTTPException(status_code=404, detail="Server not found")
    
    dirs = CONFIG_DIRS.get(source, "/etc/wdtt").split()
    listing = ""
    files = {}
    error = None
    
    try:
        async with SSHTransport(server["ip"], key_path=manager.ssh_key_path) as ssh:
            for d in dirs:
                ls = await ssh.run(f"ls -la {d} 2>/dev/null")
                if ls.stdout.strip():
                    listing += f"=== {d} ===\n{ls.stdout}\n"
                    names = await ssh.run(
                        f"find {d} -maxdepth 1 -type f -size -64k 2>/dev/null"
                    )
                    for path in (names.stdout or "").split():
                        cat = await ssh.run(f"cat '{path}' 2>/dev/null")
                        files[path] = cat.stdout or "(пусто или бинарный)"
            if not listing:
                listing = "(каталоги конфигов не найдены)"
    except Exception as e:
        error = str(e)
    
    # Парсим текущие значения для формы редактирования
    import re as _re
    awg_params = {}
    raw_content = ""
    edit_path = ""
    
    if not error:
        try:
            async with SSHTransport(server["ip"], key_path=manager.ssh_key_path) as ssh:
                conf_path = await _find_config(ssh, source)
                if conf_path:
                    edit_path = conf_path
                    cat = await ssh.run(f"cat {conf_path}")
                    raw_content = cat.stdout or ""
                    if source == "awg":
                        for key in AWG_PARAMS:
                            m = _re.search(rf"^{key}\s*=\s*(\S+)", raw_content, _re.M)
                            awg_params[key] = m.group(1) if m else ""
        except Exception:
            pass
    
    return templates.TemplateResponse(
        request=request,
        name="pages/server_configs.html",
        context={
            "server": server,
            "source": source,
            "sources": ["wdtt", "aivpn", "awg"],
            "listing": listing,
            "files": files,
            "error": error,
            "msg": request.query_params.get("msg"),
            "awg_params": awg_params,
            "raw_content": raw_content,
            "edit_path": edit_path,
            "awg_param_list": AWG_PARAMS,
            "awg_param_types": AWG_PARAM_TYPES,
            "awg_param_groups": AWG_PARAM_GROUPS,
        },
    )


# === Config editing with backup + rollback ===

import base64 as _b64
import re as _re

AWG_PARAMS = [
    "Jc", "Jmin", "Jmax",
    "S1", "S2", "S3", "S4",
    "I1", "I2", "I3", "I4", "I5",
    "H1", "H2", "H3", "H4",
    "HeaderProtectionKey",
    "ContentPaddingAddition",
    "RandomTrailers", "DisableCookies",
    "RekeyAfterTime", "RekeyTimeout", "RejectAfterTime", "KeepaliveTimeout", "MaxHandshakeAttempts",
    "MTU", "ListenPort",
]

AWG_PARAM_TYPES = {
    "Jc": "uint16", "Jmin": "uint16", "Jmax": "uint16",
    "S1": "uint16", "S2": "uint16", "S3": "uint16", "S4": "uint16",
    "I1": "cps", "I2": "cps", "I3": "cps", "I4": "cps", "I5": "cps",
    "H1": "range", "H2": "range", "H3": "range", "H4": "range",
    "HeaderProtectionKey": "base64",
    "ContentPaddingAddition": "range",
    "RandomTrailers": "toggle", "DisableCookies": "toggle",
    "RekeyAfterTime": "range", "RekeyTimeout": "range", "RejectAfterTime": "range",
    "KeepaliveTimeout": "range", "MaxHandshakeAttempts": "range",
    "MTU": "uint16", "ListenPort": "uint16",
}

AWG_PARAM_GROUPS = [
    ("Анти-DPI базовый", ["Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4"]),
    ("Маскировка handshake", ["I1", "I2", "I3", "I4", "I5"]),
    ("Идентификаторы сообщений", ["H1", "H2", "H3", "H4"]),
    ("Защита заголовков", ["HeaderProtectionKey"]),
    ("Поведение трафика", ["ContentPaddingAddition", "RandomTrailers", "DisableCookies"]),
    ("Таймеры", ["RekeyAfterTime", "RekeyTimeout", "RejectAfterTime", "KeepaliveTimeout", "MaxHandshakeAttempts"]),
    ("Интерфейс", ["MTU", "ListenPort"]),
]

CONFIG_PATHS = {
    "wdtt": ["/etc/wdtt/server.json", "/etc/wdtt/passwords.json"],
    "aivpn": ["/etc/aivpn/server.json", "/etc/aivpn/clients.json"],
    "awg": ["/etc/amnezia/amneziawg/awg0.conf", "/etc/wireguard/awg0.conf", "/etc/amnezia-wg/awg0.conf"],
}

SERVICE_CHECK = {
    "wdtt": "systemctl is-active wdtt",
    "aivpn": "systemctl is-active aivpn-server",
    "awg": "awg show awg0",
}


async def _find_config(ssh, source: str):
    """Найти первый существующий конфиг протокола."""
    for path in CONFIG_PATHS.get(source, []):
        r = await ssh.run(f"test -f {path} && echo yes")
        if r.stdout.strip() == "yes":
            return path
    return None


@app.post("/servers/{server_id}/config/apply")
async def apply_config(request: Request, server_id: str):
    """Применить правку конфига: бекап → правка → рестарт → проверка → откат."""
    from urllib.parse import quote
    from datetime import datetime as _dt
    
    form = await request.form()
    source = form.get("source", "awg")
    
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        return RedirectResponse("/servers?error=Server+not+found", status_code=303)
    
    try:
        async with SSHTransport(server["ip"], key_path=manager.ssh_key_path) as ssh:
            conf_path = await _find_config(ssh, source)
            if not conf_path:
                return RedirectResponse(
                    f"/servers/{server_id}/configs?source={source}&msg=" + quote("Конфиг не найден"),
                    status_code=303,
                )
            
            # 1. Текущее содержимое
            cur = await ssh.run(f"cat {conf_path}")
            old_conf = cur.stdout
            
            # 2. Формируем новый конфиг
            if source == "awg":
                new_conf = old_conf
                for key in AWG_PARAMS:
                    val = form.get(key)
                    if val and val.strip():
                        param_type = AWG_PARAM_TYPES.get(key, "uint16")
                        valid = True
                        
                        if param_type == "uint16":
                            try:
                                n = int(val)
                                if not (0 <= n <= 65535):
                                    valid = False
                            except ValueError:
                                valid = False
                            if not valid:
                                return RedirectResponse(
                                    f"/servers/{server_id}/configs?source={source}&msg=" + quote(f"{key}: число 0-65535"),
                                    status_code=303,
                                )
                            new_conf = _re.sub(rf"^{key}\s*=\s*\S+", f"{key} = {val}", new_conf, flags=_re.M)
                        
                        elif param_type == "range":
                            if not _re.match(r"^\d+(-\d+)?$", val):
                                return RedirectResponse(
                                    f"/servers/{server_id}/configs?source={source}&msg=" + quote(f"{key}: формат a или a-b"),
                                    status_code=303,
                                )
                            new_conf = _re.sub(rf"^{key}\s*=\s*\S+", f"{key} = {val}", new_conf, flags=_re.M)
                        
                        elif param_type == "cps":
                            # I1-I5: формат "3:40" или "2:30,5:40"
                            if not _re.match(r"^\d+:\d+(,\d+:\d+)*$", val):
                                return RedirectResponse(
                                    f"/servers/{server_id}/configs?source={source}&msg=" + quote(f"{key}: формат count:size или count:size,count:size"),
                                    status_code=303,
                                )
                            new_conf = _re.sub(rf"^{key}\s*=\s*\S+", f"{key} = {val}", new_conf, flags=_re.M)
                        
                        elif param_type == "base64":
                            # HeaderProtectionKey: 44 символа base64
                            if not _re.match(r"^[A-Za-z0-9+/]{43}=$", val):
                                return RedirectResponse(
                                    f"/servers/{server_id}/configs?source={source}&msg=" + quote(f"{key}: base64 44 символа"),
                                    status_code=303,
                                )
                            new_conf = _re.sub(rf"^{key}\s*=\s*\S+", f"{key} = {val}", new_conf, flags=_re.M)
                        
                        elif param_type == "toggle":
                            if val not in ("on", "off"):
                                return RedirectResponse(
                                    f"/servers/{server_id}/configs?source={source}&msg=" + quote(f"{key}: on или off"),
                                    status_code=303,
                                )
                            new_conf = _re.sub(rf"^{key}\s*=\s*\S+", f"{key} = {val}", new_conf, flags=_re.M)
            else:
                new_conf = form.get("raw_config", "")
                try:
                    import json as _j
                    _j.loads(new_conf)
                except Exception as e:
                    return RedirectResponse(
                        f"/servers/{server_id}/configs?source={source}&msg=" + quote(f"Невалидный JSON: {str(e)[:100]}"),
                        status_code=303,
                    )
            
            if new_conf == old_conf:
                return RedirectResponse(
                    f"/servers/{server_id}/configs?source={source}&msg=Без+изменений",
                    status_code=303,
                )
            
            # 3. Бекап
            ts = _dt.now().strftime("%Y%m%d-%H%M%S")
            backup_path = f"{conf_path}.hydra-backup-{ts}"
            await ssh.run(f"cp -p {conf_path} {backup_path}")
            
            # 4. Запись нового конфига (base64 для надёжности)
            b64 = _b64.b64encode(new_conf.encode()).decode()
            await ssh.run(f"echo '{b64}' | base64 -d > {conf_path}")
            
            # 5. Перезапуск сервиса
            restart_cmd = PROTOCOL_RESTART_CMDS.get(source, "true")
            await ssh.run(restart_cmd)
            
            # 6. Проверка
            import asyncio as _aio
            await _aio.sleep(2)
            check = await ssh.run(SERVICE_CHECK.get(source, "true"))
            
            if check.exit_code != 0 or "inactive" in check.stdout:
                # Откат
                await ssh.run(f"cp -p {backup_path} {conf_path}")
                await ssh.run(restart_cmd)
                msg = quote(f"Сервис не поднялся — выполнен откат из {backup_path}")
            else:
                msg = f"config_applied_{source}"
    except Exception as e:
        msg = quote("SSH ошибка: " + str(e))
    
    return RedirectResponse(f"/servers/{server_id}/configs?source={source}&msg={msg}", status_code=303)


# === Database console (read-only) ===


@app.post("/database/query")
async def database_query(request: Request, query: str = Form(...)):
    """Execute read-only SQL query."""
    import base64 as _b64mod
    import json as _jsonmod
    from urllib.parse import quote
    q = query.strip().rstrip(";")
    ql = q.lower()
    if not (ql.startswith("select") or ql.startswith("pragma")):
        return RedirectResponse("/database?error=" + quote("Разрешены только SELECT и PRAGMA"), status_code=303)
    # Word-boundary check: не блокирует колонки вида updated_at
    if _re.search(r"\b(insert|update|delete|drop|alter|create|attach|detach|vacuum|replace)\b", ql):
        return RedirectResponse("/database?error=" + quote("Запрещённая операция в запросе"), status_code=303)
    try:
        rows = await db.fetchall(q)
        b64 = _b64mod.b64encode(_jsonmod.dumps(rows, default=str).encode()).decode()
        return RedirectResponse("/database?result=" + quote(b64), status_code=303)
    except Exception as e:
        return RedirectResponse("/database?error=" + quote(str(e)), status_code=303)


@app.get("/database/backup")
async def database_backup():
    """Download panel.db copy."""
    import os as _os
    from datetime import datetime as _dt
    from fastapi.responses import FileResponse
    db_path = _os.environ.get("HYDRA_DB_PATH", "./panel.db")
    if not _os.path.exists(db_path):
        raise HTTPException(status_code=404, detail="Database not found")
    return FileResponse(
        db_path,
        media_type="application/octet-stream",
        filename=f"hydra-backup-{_dt.now().strftime('%Y%m%d-%H%M%S')}.db",
    )


# === Hydra Auth v1 ===

from .core import auth, secrets, recovery

AUTH_PUBLIC_PATHS = {"/login", "/setup", "/favicon.ico"}
AUTH_PUBLIC_PREFIXES = ("/api/v1/agent/", "/api/v1/client/", "/static")


@app.on_event("startup")
async def _init_auth_tables():
    await auth.ensure_tables(db)
    await secrets.ensure_table(db)


@app.middleware("http")
async def hydra_auth_middleware(request: Request, call_next):
    path = request.url.path
    if path in AUTH_PUBLIC_PATHS or path.startswith(AUTH_PUBLIC_PREFIXES):
        return await call_next(request)
    if path == "/setup":
        if await auth.has_admin(db):
            return RedirectResponse("/login", status_code=303)
        return await call_next(request)
    token = request.cookies.get("hydra_session")
    if token and await auth.get_session(db, token):
        return await call_next(request)
    cli = request.headers.get("x-hydra-token")
    if cli and await auth.check_cli_token(db, cli):
        return await call_next(request)
    if path.startswith("/api/"):
        return JSONResponse({"detail": "Unauthorized"}, status_code=401)
    return RedirectResponse("/login", status_code=303)


def _client_ip(request: Request) -> str:
    return request.client.host if request.client else ""


def _set_session_cookie(resp, token: str, remember: bool):
    resp.set_cookie(
        "hydra_session", token,
        httponly=True, samesite="lax", path="/",
        max_age=30 * 24 * 3600 if remember else 12 * 3600,
    )
    return resp


@app.get("/login", response_class=HTMLResponse)
async def login_page(request: Request, error: Optional[str] = None):
    return templates.TemplateResponse(
        request=request,
        name="pages/login.html",
        context={"error": error, "no_admin": not await auth.has_admin(db)},
    )


@app.post("/login")
async def login_submit(request: Request):
    from urllib.parse import quote
    form = await request.form()
    password = form.get("password", "")
    remember = form.get("remember") == "on"
    ip = _client_ip(request)
    
    if await auth.is_rate_limited(db, ip):
        return RedirectResponse("/login?error=" + quote("Слишком много попыток. Повтори через 15 минут."), status_code=303)
    
    ok = await auth.check_admin_password(db, password)
    await auth.record_attempt(db, ip, ok)
    if not ok:
        return RedirectResponse("/login?error=" + quote("Неверный пароль"), status_code=303)
    
    token = await auth.create_session(db, remember, request.headers.get("user-agent", "")[:200], ip)
    resp = RedirectResponse("/", status_code=303)
    return _set_session_cookie(resp, token, remember)


@app.get("/setup", response_class=HTMLResponse)
async def setup_page(request: Request):
    return templates.TemplateResponse(request=request, name="pages/setup.html", context={})


@app.post("/setup")
async def setup_submit(request: Request, password: str = Form(...), password2: str = Form(...)):
    from urllib.parse import quote
    if await auth.has_admin(db):
        return RedirectResponse("/login", status_code=303)
    if len(password) < 8:
        return RedirectResponse("/setup?error=" + quote("Пароль минимум 8 символов"), status_code=303)
    if password != password2:
        return RedirectResponse("/setup?error=" + quote("Пароли не совпадают"), status_code=303)
    
    await auth.set_admin_password(db, password)
    cli_token = await auth.get_or_create_cli_token(db)
    token = await auth.create_session(db, True, request.headers.get("user-agent", "")[:200], _client_ip(request))
    
    resp = templates.TemplateResponse(
        request=request,
        name="pages/setup_done.html",
        context={"cli_token": cli_token},
    )
    return _set_session_cookie(resp, token, True)


@app.post("/logout")
async def logout_submit(request: Request):
    token = request.cookies.get("hydra_session")
    if token:
        await auth.delete_session(db, auth.token_hash(token))
    resp = RedirectResponse("/login", status_code=303)
    resp.delete_cookie("hydra_session", path="/")
    return resp


@app.get("/sessions", response_class=HTMLResponse)
async def sessions_page(request: Request):
    current = request.cookies.get("hydra_session") or ""
    sessions = await auth.list_sessions(db)
    return templates.TemplateResponse(
        request=request,
        name="pages/sessions.html",
        context={"sessions": sessions, "current_hash": auth.token_hash(current)},
    )


@app.post("/sessions/revoke")
async def sessions_revoke(request: Request, token_hash: str = Form(...)):
    current = request.cookies.get("hydra_session") or ""
    is_current = auth.token_hash(current) == token_hash
    await auth.delete_session(db, token_hash)
    if is_current:
        resp = RedirectResponse("/login", status_code=303)
        resp.delete_cookie("hydra_session", path="/")
        return resp
    return RedirectResponse("/sessions", status_code=303)


@app.post("/sessions/revoke-all")
async def sessions_revoke_all(request: Request):
    await auth.delete_all_sessions(db)
    resp = RedirectResponse("/login", status_code=303)
    resp.delete_cookie("hydra_session", path="/")
    return resp


@app.post("/cli/rotate")
async def cli_rotate(request: Request):
    token = await auth.rotate_cli_token(db)
    return templates.TemplateResponse(
        request=request,
        name="pages/setup_done.html",
        context={"cli_token": token, "rotated": True},
    )


# === Settings page ===

@app.get("/settings", response_class=HTMLResponse)
async def settings_page(request: Request):
    """Просмотр и редактирование panel.yaml."""
    import os as _os
    
    yaml_path = _os.environ.get("HYDRA_PANEL_YAML", "./panel.yaml")
    content = ""
    exists = _os.path.exists(yaml_path)
    
    if exists:
        try:
            with open(yaml_path, 'r', encoding='utf-8') as f:
                content = f.read()
        except Exception as e:
            content = f"# Ошибка чтения: {e}"
    else:
        content = """# Hydra Panel Configuration
# Создай файл для настройки параметров

panel_url: "http://localhost:8000"
timezone: "Europe/Moscow"
max_connections_per_key: 3
alert_webhook: ""
"""
    
    return templates.TemplateResponse(
        request=request,
        name="pages/settings.html",
        context={"content": content, "yaml_path": yaml_path, "exists": exists},
    )


@app.post("/settings")
async def settings_save(request: Request, content: str = Form(...)):
    """Сохранить panel.yaml с бекапом."""
    import os as _os
    from datetime import datetime as _dt
    from urllib.parse import quote
    
    yaml_path = _os.environ.get("HYDRA_PANEL_YAML", "./panel.yaml")
    backup_path = None
    
    # Валидация YAML (если PyYAML установлен)
    try:
        import yaml as _yaml
        _yaml.safe_load(content)
    except ImportError:
        pass
    except Exception as e:
        return RedirectResponse("/settings?error=" + quote(f"Невалидный YAML: {str(e)[:100]}"), status_code=303)
    
    # Бекап
    if _os.path.exists(yaml_path):
        ts = _dt.now().strftime("%Y%m%d-%H%M%S")
        backup_path = f"{yaml_path}.backup-{ts}"
        try:
            _os.rename(yaml_path, backup_path)
        except Exception as e:
            return RedirectResponse("/settings?error=" + quote(f"Ошибка бекапа: {e}"), status_code=303)
    
    # Запись
    try:
        with open(yaml_path, 'w', encoding='utf-8') as f:
            f.write(content)
        msg = "settings_saved"
    except Exception as e:
        # Откат
        if backup_path and _os.path.exists(backup_path):
            _os.rename(backup_path, yaml_path)
        msg = quote(f"Ошибка записи: {e}")
    
    return RedirectResponse(f"/settings?msg={msg}", status_code=303)


# === Onboarding v1: bootstrap ключа + проверка SSH ===

import asyncssh as _asyncssh


async def _ssh_run_once(ip, port, key_path, command, password=None, timeout=8):
    """Одноразовое SSH-подключение: пароль (bootstrap) или ключ панели.
    
    Security (Р-25): known_hosts=None используется ТОЛЬКО для первоначального
    onboarding сервера, когда пароль ещё не заменён на ключ панели. Это осознанный
    компромисс: при первом подключении нет возможности проверить хост-ключ.
    После onboarding используется нормальный SSH с ключом и known_hosts.
    """
    import asyncio as _aio
    # known_hosts=None — осознанный выбор для bootstrap (Р-25)
    kwargs = {"host": ip, "port": int(port or 22), "username": "root", "known_hosts": None}
    if password:
        kwargs["password"] = password
    else:
        kwargs["client_keys"] = [key_path]

    async def _do():
        async with _asyncssh.connect(**kwargs) as conn:
            res = await conn.run(command)
            return res.exit_status, res.stdout or "", res.stderr or ""

    return await _aio.wait_for(_do(), timeout=timeout)


def _read_panel_pubkey():
    """Публичный ключ панели: читает .pub или выводит из приватного."""
    import os as _osx
    import subprocess as _sp
    key_path = manager.ssh_key_path
    pub = key_path + ".pub"
    if _osx.path.exists(pub):
        with open(pub) as f:
            line = f.read().strip()
        if line:
            return line
    proc = _sp.run(["ssh-keygen", "-y", "-f", key_path], capture_output=True, text=True)
    if proc.returncode == 0 and proc.stdout.strip():
        line = proc.stdout.strip() + " hydra-panel"
        with open(pub, "w") as f:
            f.write(line + "\n")
        return line
    return None


@app.post("/servers/{server_id}/test-ssh")
async def test_ssh_form(server_id: str):
    """Проверка SSH-доступа ключом панели (с учётом ssh_port)."""
    from urllib.parse import quote
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        return RedirectResponse("/servers?error=Server+not+found", status_code=303)
    try:
        code, out, err = await _ssh_run_once(
            server["ip"], server["ssh_port"], manager.ssh_key_path, "echo hydra-ok"
        )
        if code == 0 and out.strip() == "hydra-ok":
            msg = "ssh_ok"
            await db.execute("UPDATE servers SET status = 'active' WHERE id = ?", server_id)
        else:
            msg = quote("SSH ответил ошибкой: " + (err or out)[:150])
            await db.execute("UPDATE servers SET status = 'offline' WHERE id = ?", server_id)
    except TimeoutError:
        msg = quote("SSH недоступен: таймаут подключения 8 сек (" + server["ip"] + ":" + str(server["ssh_port"]) + ")")
        await db.execute("UPDATE servers SET status = 'offline' WHERE id = ?", server_id)
    except Exception as e:
        msg = quote("SSH недоступен: " + str(e)[:150])
        await db.execute("UPDATE servers SET status = 'offline' WHERE id = ?", server_id)
    await db.commit()
    return RedirectResponse(f"/servers/{server_id}?msg={msg}", status_code=303)

from datetime import datetime, timedelta


@app.on_event("startup")
async def _init_clients_schema():
    await db.execute("""
    CREATE TABLE IF NOT EXISTS clients (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        telegram_id INTEGER UNIQUE,
        tg_username TEXT,
        display_name TEXT,
        status TEXT NOT NULL DEFAULT 'active',
        notes TEXT,
        created_at TEXT NOT NULL
    )""")
    cols = [r["name"] for r in await db.fetchall("PRAGMA table_info(access_keys)")]
    if "client_id" not in cols:
        await db.execute("ALTER TABLE access_keys ADD COLUMN client_id INTEGER REFERENCES clients(id) ON DELETE SET NULL")
    await db.commit()


# === Clients (people) ===

@app.get("/clients", response_class=HTMLResponse)
async def clients_people_list(request: Request, msg: Optional[str] = None):
    clients = await db.fetchall(
        """SELECT cl.*,
                  (SELECT COUNT(*) FROM access_keys ak WHERE ak.client_id = cl.id) AS keys_total,
                  (SELECT COUNT(*) FROM access_keys ak WHERE ak.client_id = cl.id
                     AND ak.revoked_at IS NULL AND (ak.expires_at IS NULL OR ak.expires_at > datetime('now'))) AS keys_active
           FROM clients cl ORDER BY cl.created_at DESC"""
    )
    return templates.TemplateResponse(
        request=request, name="pages/clients.html", context={"clients": clients, "msg": msg})


@app.post("/clients/create")
async def create_client_person(display_name: str = Form(...), telegram_id: str = Form(""), tg_username: str = Form(""), notes: str = Form("")):
    from urllib.parse import quote
    tg = int(telegram_id) if telegram_id.strip().lstrip('-').isdigit() else None
    try:
        await db.execute(
            "INSERT INTO clients (telegram_id, tg_username, display_name, status, notes, created_at) VALUES (?,?,?,?,?,datetime('now'))",
            tg, tg_username.strip().lstrip('@') or None, display_name.strip(), 'active', notes.strip() or None)
        await db.commit()
        msg = "client_created"
    except Exception as e:
        msg = quote("Ошибка: " + str(e)[:120])
    return RedirectResponse(f"/clients?msg={msg}", status_code=303)


@app.get("/clients/{client_id}", response_class=HTMLResponse)
async def client_detail_page(request: Request, client_id: int, msg: Optional[str] = None):
    client = await db.fetchone("SELECT * FROM clients WHERE id = ?", client_id)
    if not client:
        raise HTTPException(status_code=404, detail="Client not found")
    keys = await db.fetchall(
        """SELECT ak.*, (SELECT COUNT(*) FROM device_registrations dr WHERE dr.key_id = ak.key_id) AS device_count
           FROM access_keys ak WHERE ak.client_id = ? ORDER BY ak.created_at DESC""", client_id)
    devices = await db.fetchall(
        """SELECT dr.*, ak.key_id AS key_ref FROM device_registrations dr
           JOIN access_keys ak ON ak.key_id = dr.key_id WHERE ak.client_id = ? ORDER BY dr.registered_at DESC""", client_id)
    return templates.TemplateResponse(
        request=request, name="pages/client_detail.html",
        context={"client": client, "keys": keys, "devices": devices, "msg": msg,
                 "now": datetime.now().strftime('%Y-%m-%d %H:%M:%S')})


@app.post("/clients/{client_id}/update")
async def update_client(client_id: int, display_name: str = Form(...), telegram_id: str = Form(""), tg_username: str = Form(""), notes: str = Form("")):
    tg = int(telegram_id) if telegram_id.strip().lstrip('-').isdigit() else None
    await db.execute("UPDATE clients SET display_name=?, telegram_id=?, tg_username=?, notes=? WHERE id=?",
                     display_name.strip(), tg, tg_username.strip().lstrip('@') or None, notes.strip() or None, client_id)
    await db.commit()
    return RedirectResponse(f"/clients/{client_id}?msg=client_updated", status_code=303)


@app.post("/clients/{client_id}/toggle-block")
async def toggle_block_client(client_id: int):
    cl = await db.fetchone("SELECT status FROM clients WHERE id = ?", client_id)
    new = 'blocked' if (cl and cl['status'] == 'active') else 'active'
    await db.execute("UPDATE clients SET status=? WHERE id=?", new, client_id)
    await db.commit()
    return RedirectResponse(f"/clients/{client_id}?msg=client_{new}", status_code=303)


@app.post("/clients/{client_id}/delete")
async def delete_client_cascade(client_id: int):
    keys = await db.fetchall("SELECT key_id FROM access_keys WHERE client_id = ?", client_id)
    for k in keys:
        await db.execute("DELETE FROM device_connections WHERE key_id = ?", k["key_id"])
        await db.execute("DELETE FROM device_registrations WHERE key_id = ?", k["key_id"])
        await db.execute("DELETE FROM key_server_clients WHERE key_id = ?", k["key_id"])
    await db.execute("DELETE FROM access_keys WHERE client_id = ?", client_id)
    await db.execute("DELETE FROM clients WHERE id = ?", client_id)
    await db.commit()
    return RedirectResponse("/clients?msg=client_deleted", status_code=303)


# === Key lifecycle ===

@app.post("/keys/create")
async def create_key_for_client(client_id: int = Form(...), expire_days: int = Form(30), max_devices: int = Form(3), custom_date: str = Form("")):
    import secrets as _sec
    from urllib.parse import quote
    client = await db.fetchone("SELECT id FROM clients WHERE id = ?", client_id)
    if not client:
        return RedirectResponse("/keys?msg=" + quote("Сначала создай клиента"), status_code=303)
    if custom_date.strip():
        cd = custom_date.strip().replace('T', ' ')
        expires = cd if len(cd) > 10 else cd + ' 23:59:59'
    else:
        expires = (datetime.now() + timedelta(days=max(1, expire_days))).strftime('%Y-%m-%d %H:%M:%S')
    key_id = _sec.token_urlsafe(32)
    await db.execute(
        "INSERT INTO access_keys (key_id, client_id, max_devices, expires_at, created_at) VALUES (?,?,?,?,datetime('now'))",
        key_id, client_id, max(1, max_devices), expires)
    await db.commit()
    return RedirectResponse(f"/clients/{client_id}?msg=key_created", status_code=303)


@app.post("/keys/{key_id}/extend")
async def extend_key_form(key_id: str, days: int = Form(30)):
    from urllib.parse import quote
    key = await db.fetchone("SELECT expires_at, client_id FROM access_keys WHERE key_id = ?", key_id)
    if not key:
        return RedirectResponse("/keys?msg=" + quote("Ключ не найден"), status_code=303)
    now = datetime.now()
    fmt = '%Y-%m-%d %H:%M:%S'
    try:
        cur = datetime.strptime((key["expires_at"] or '').replace('T', ' ')[:19], fmt)
    except Exception:
        cur = now
    base = cur if cur > now else now
    new_exp = (base + timedelta(days=max(1, days))).strftime(fmt)
    await db.execute("UPDATE access_keys SET expires_at = ? WHERE key_id = ?", new_exp, key_id)
    await db.commit()
    target = f"/clients/{key['client_id']}" if key["client_id"] else "/keys"
    return RedirectResponse(f"{target}?msg=key_extended", status_code=303)


@app.get("/keys", response_class=HTMLResponse)
async def keys_list(request: Request, msg: Optional[str] = None):
    keys = await db.fetchall(
        """SELECT ak.*,
                  (SELECT COUNT(*) FROM device_registrations dr WHERE dr.key_id = ak.key_id) AS device_count,
                  cl.display_name AS client_name, cl.telegram_id AS client_tg, cl.id AS client_pk
           FROM access_keys ak LEFT JOIN clients cl ON cl.id = ak.client_id
           ORDER BY ak.created_at DESC"""
    )
    clients = await db.fetchall("SELECT id, display_name, tg_username FROM clients ORDER BY display_name")
    now_str = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    return templates.TemplateResponse(
        request=request, name="pages/keys.html",
        context={"keys": keys, "now": now_str, "msg": msg, "clients": clients})


@app.post("/devices/create")
async def create_client_form(
    key_id: str = Form(...),
    device_id: str = Form(...),
    device_name: str = Form(""),
    last_ip: str = Form(""),
):
    """Добавить устройство вручную (например, перенос из старой системы)."""
    key = await db.fetchone("SELECT key_id FROM access_keys WHERE key_id = ?", key_id)
    if not key:
        return RedirectResponse("/clients?msg=client_nokey", status_code=303)
    try:
        await db.execute(
            """INSERT INTO device_registrations
               (key_id, device_id, device_name, last_ip, registered_at, last_seen_at)
               VALUES (?, ?, ?, ?, datetime('now'), datetime('now'))""",
            key_id, device_id, device_name or None, last_ip or None,
        )
        await db.commit()
        msg = "client_added"
    except Exception:
        msg = "client_exists"
    return RedirectResponse(f"/clients?msg={msg}", status_code=303)

@app.post("/devices/delete")
async def delete_client_form(key_id: str = Form(...), device_id: str = Form(...)):
    """Удалить устройство вместе с историей подключений."""
    await db.execute(
        "DELETE FROM device_connections WHERE key_id = ? AND device_id = ?",
        key_id, device_id,
    )
    await db.execute(
        "DELETE FROM device_registrations WHERE key_id = ? AND device_id = ?",
        key_id, device_id,
    )
    await db.commit()
    return RedirectResponse("/clients?msg=client_deleted", status_code=303)


# === SSH operations (web UI) ===

from .ssh import SSHTransport

PROTOCOL_RESTART_CMDS = {
    "wdtt": "systemctl restart wdtt",
    "aivpn": "systemctl restart aivpn-server",
    "awg": "awg-quick down awg0 && awg-quick up awg0",
}

PROTOCOL_LOG_CMDS = {
    "agent": '{ echo "=== hydra-agent.log (last 200) ==="; tail -200 /var/log/hydra-agent.log 2>/dev/null; echo; echo "=== cron runs (hydra) ==="; journalctl -n 300 --no-pager 2>/dev/null | grep -i hydra | tail -20; echo; echo "=== buffer status ==="; ls -la /var/lib/hydra-agent/ 2>/dev/null; wc -l /var/lib/hydra-agent/buffer.ndjson 2>/dev/null; }',
    "wdtt": r'{ echo "=== События (подключения/ошибки, без [СТАТ]) ==="; journalctl -u wdtt -n 3000 --no-pager -q | grep -v "[СТАТ]" | tail -80; echo; echo "=== Свежая статистика (последние 10) ==="; journalctl -u wdtt -n 40 --no-pager -q | grep "\[СТАТ\]" | tail -10; }',
    "aivpn": '{ echo "=== События (без DEBUG) ==="; journalctl -u aivpn-server -n 400 --no-pager -q | grep -v " DEBUG " | tail -100; }',
    "awg": '{ awg show awg0 2>/dev/null; echo; echo "=== dmesg (awg0) ==="; dmesg | grep awg0 | tail -100; }',
}



# === Backups: auto + rotation + restore + offsite ===

import os
from fastapi import UploadFile, File
from .core import backups

DB_FILE_PATH = os.environ.get("HYDRA_DB_PATH", "./panel.db")


def _backup_cfg() -> dict:
    cfg = {"keep": 3, "github_repo": "", "github_token": ""}
    try:
        import yaml as _y
        with open(os.environ.get("HYDRA_PANEL_YAML", "./panel.yaml")) as f:
            data = _y.safe_load(f) or {}
        b = data.get("backup") or {}
        for k in ("keep", "github_repo", "github_token"):
            if k in b:
                cfg[k] = b[k]
    except Exception:
        pass
    cfg["github_repo"] = os.environ.get("HYDRA_GH_REPO") or cfg["github_repo"]
    cfg["github_token"] = os.environ.get("HYDRA_GH_TOKEN") or cfg["github_token"]
    return cfg


async def _backup_worker():
    import asyncio as _aio
    while True:
        try:
            today = datetime.now().strftime("%Y-%m-%d")
            has_today = any(b["auto"] and b["mtime"].startswith(today) for b in backups.list_backups())
            if not has_today:
                repo, token, meta = await _get_github_creds()
                name = backups.create_backup(DB_FILE_PATH)
                backups.rotate(int((meta or {}).get("keep", 3)))
                if repo and token:
                    try:
                        backups.push_github(repo, token, name)
                    except Exception:
                        pass
                    try:
                        await _push_recovery_bundle()
                    except Exception:
                        pass
        except Exception:
            pass
        await _aio.sleep(3600)


@app.on_event("startup")
async def _start_backup_worker():
    import asyncio as _aio
    _aio.create_task(_backup_worker())


@app.get("/database", response_class=HTMLResponse)
async def database_console(request: Request, result: Optional[str] = None, error: Optional[str] = None, msg: Optional[str] = None):
    import base64 as _b64mod
    import json as _jsonmod
    tables = await db.fetchall(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
    )
    decoded_result = None
    if result:
        try:
            decoded_result = _jsonmod.loads(_b64mod.b64decode(result).decode())
        except Exception:
            decoded_result = None
    return templates.TemplateResponse(
        request=request,
        name="pages/database.html",
        context={"tables": tables, "result": decoded_result, "error": error, "msg": msg,
                 "backups": backups.list_backups(), "backup_cfg": _backup_cfg()},
    )


@app.post("/database/backup-now")
async def backup_now():
    from urllib.parse import quote
    cfg = _backup_cfg()
    name = backups.create_backup(DB_FILE_PATH)
    backups.rotate(int(cfg.get("keep", 3)))
    extra = ""
    if cfg.get("github_repo") and cfg.get("github_token"):
        try:
            res = backups.push_github(cfg["github_repo"], cfg["github_token"], name)
            extra = ", GitHub: ok" if res.get("ok") else f", GitHub: {res.get('error')}"
        except Exception as e:
            extra = f", GitHub ошибка: {str(e)[:60]}"
    return RedirectResponse("/database?msg=" + quote(f"Бэкап {name} создан{extra}"), status_code=303)


@app.post("/database/restore")
async def database_restore(name: str = Form(...)):
    from urllib.parse import quote
    try:
        backups.restore(name, DB_FILE_PATH)
        msg = quote(f"Восстановлено из {name}. Перезапусти панель и войди заново")
    except Exception as e:
        msg = quote("Ошибка восстановления: " + str(e)[:120])
    return RedirectResponse(f"/database?msg={msg}", status_code=303)


@app.post("/database/backup-upload")
async def backup_upload(file: UploadFile = File(...)):
    from urllib.parse import quote
    backups.ensure_dir()
    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    name = f"uploaded-{ts}.db"
    path = os.path.join(backups.BACKUP_DIR, name)
    data = await file.read()
    if len(data) > 500 * 1024 * 1024:
        return RedirectResponse("/database?msg=" + quote("Файл больше 500 МБ"), status_code=303)
    with open(path, "wb") as f:
        f.write(data)
    if not backups.verify(path):
        os.remove(path)
        return RedirectResponse("/database?msg=" + quote("Загруженный файл — не валидная SQLite БД"), status_code=303)
    return RedirectResponse("/database?msg=" + quote(f"Бэкап {name} загружен — можно восстанавливать"), status_code=303)


@app.post("/database/backup-delete")
async def backup_delete(name: str = Form(...)):
    from urllib.parse import quote
    path = os.path.join(backups.BACKUP_DIR, name)
    if os.path.exists(path):
        os.remove(path)
        msg = quote(f"Бэкап {name} удалён")
    else:
        msg = quote("Файл не найден")
    return RedirectResponse(f"/database?msg={msg}", status_code=303)


@app.get("/database/backup-file")
async def backup_file(name: str):
    from fastapi.responses import FileResponse
    path = os.path.join(backups.BACKUP_DIR, name)
    if not os.path.exists(path):
        raise HTTPException(status_code=404, detail="Not found")
    return FileResponse(path, media_type="application/octet-stream", filename=name)


# === Backup settings wizard (GitHub offsite) ===

async def _get_github_creds():
    """Читает GitHub credentials: secrets (зашифровано) -> env fallback."""
    token, meta = await secrets.get_secret(db, "github")
    if token and meta and meta.get("repo"):
        return meta["repo"], token, meta
    repo = os.environ.get("HYDRA_GH_REPO")
    tok = os.environ.get("HYDRA_GH_TOKEN")
    if repo and tok:
        return repo, tok, {"source": "env"}
    return None, None, {}


def _github_ping(repo: str, token: str):
    """Проверка доступа к репо без создания файлов."""
    import json
    import urllib.request
    import urllib.error
    req = urllib.request.Request(
        f"https://api.github.com/repos/{repo}",
        headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "User-Agent": "hydra-panel"},
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            data = json.loads(r.read())
            perms = data.get("permissions", {})
            if not perms.get("push"):
                return False, "нет права push (нужно Contents: Read and write)"
            return True, f"доступ ok, приватность: {'private' if data.get('private') else 'PUBLIC!'}"
    except urllib.error.HTTPError as e:
        return False, f"HTTP {e.code}: {'токен невалиден' if e.code == 401 else 'репо не найдено или нет доступа' if e.code == 404 else e.reason}"
    except Exception as e:
        return False, f"сеть: {str(e)[:80]}"


@app.get("/settings/backup", response_class=HTMLResponse)
async def settings_backup_page(request: Request, msg: Optional[str] = None):
    repo, token, meta = await _get_github_creds()
    _rec_tok, _rec_meta = await secrets.get_secret(db, "recovery")
    return templates.TemplateResponse(
        request=request,
        name="pages/settings_backup.html",
        context={
            "msg": msg,
            "configured": bool(repo and token),
            "repo": repo or "",
            "meta": meta,
            "source": meta.get("source", "secrets") if meta else "secrets",
            "fingerprint": secrets.master_fingerprint(),
            "recovery_set": (_rec_meta or {}).get("set_at"),
        },
    )


@app.post("/settings/backup/test")
async def settings_backup_test(repo: str = Form(...), token: str = Form(...)):
    from urllib.parse import quote
    ok, detail = _github_ping(repo.strip(), token.strip())
    msg = quote(f"Проверка: {detail}") if ok else quote(f"Ошибка: {detail}")
    return RedirectResponse(f"/settings/backup?msg={msg}", status_code=303)


@app.post("/settings/backup/save")
async def settings_backup_save(repo: str = Form(...), token: str = Form(...), keep: int = Form(3)):
    from urllib.parse import quote
    repo = repo.strip()
    token = token.strip()
    ok, detail = _github_ping(repo, token)
    if not ok:
        return RedirectResponse("/settings/backup?msg=" + quote(f"Не сохранено: {detail}"), status_code=303)
    await secrets.set_secret(db, "github", token, {"repo": repo, "keep": keep, "saved_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S")})
    return RedirectResponse("/settings/backup?msg=" + quote(f"Сохранено зашифрованно. {detail}"), status_code=303)


@app.post("/settings/backup/push-now")
async def settings_backup_push_now():
    from urllib.parse import quote
    repo, token, meta = await _get_github_creds()
    if not repo or not token:
        return RedirectResponse("/settings/backup?msg=" + quote("Сначала сохрани credentials"), status_code=303)
    name = backups.create_backup(DB_FILE_PATH)
    keep = int((meta or {}).get("keep", 3))
    backups.rotate(keep)
    try:
        res = backups.push_github(repo, token, name)
        bundle_detail = await _push_recovery_bundle()
        msg = quote(f"{name} -> GitHub: {'ok' if res.get('ok') else res.get('error')}; {bundle_detail}")
    except Exception as e:
        msg = quote(f"{name} -> ошибка: {str(e)[:80]}")
    return RedirectResponse(f"/settings/backup?msg={msg}", status_code=303)


@app.post("/settings/backup/delete")
async def settings_backup_delete():
    from urllib.parse import quote
    await secrets.delete_secret(db, "github")
    return RedirectResponse("/settings/backup?msg=" + quote("GitHub credentials удалены из панели (env fallback останется, если задан)"), status_code=303)


# === Recovery passphrase + bundle ===

async def _push_recovery_bundle():
    """Собрать и запушить recovery-bundle.enc (ключи под passphrase)."""
    repo, token, meta = await _get_github_creds()
    pass_ph, rmeta = await secrets.get_secret(db, "recovery")
    if not (repo and token and pass_ph):
        return "бандл: нет passphrase или credentials"
    payload = recovery.build_bundle_payload(
        manager.ssh_key_path, secrets.MASTER_KEY_PATH, {"repo": repo, "token": token}
    )
    blob = recovery.encrypt_bundle(payload, pass_ph)
    path = os.path.join(backups.BACKUP_DIR, recovery.BUNDLE_NAME)
    with open(path, "wb") as f:
        f.write(blob)
    try:
        res = backups.push_github(repo, token, recovery.BUNDLE_NAME, compress=False)
        return "бандл в GitHub: ok" if res.get("ok") else f"бандл: {res.get('error')}"
    except Exception as e:
        return f"бандл ошибка: {str(e)[:60]}"


@app.post("/settings/backup/passphrase")
async def settings_backup_passphrase(passphrase: str = Form(...)):
    from urllib.parse import quote
    if len(passphrase) < 12:
        return RedirectResponse("/settings/backup?msg=" + quote("Passphrase минимум 12 символов (лучше сгенерировать в менеджере паролей)"), status_code=303)
    await secrets.set_secret(db, "recovery", passphrase, {"set_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S")})
    detail = await _push_recovery_bundle()
    return RedirectResponse(
        "/settings/backup?msg=" + quote(f"Passphrase установлена. {detail}. ЗАПИШИ её во внешний сейф — панель не покажет её снова."),
        status_code=303,
    )
