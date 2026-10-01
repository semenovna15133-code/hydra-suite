import re, shutil, sys

PATH = 'hydra/panel.py'
shutil.copy(PATH, PATH + '.bak3b')
src = open(PATH, encoding='utf-8').read()
db_src = open('hydra/core/db.py', encoding='utf-8').read()
assert 'async def fetchall' in db_src, 'db.fetchall not found'

# --- op1: create_key_ui -> bind after commit ---
pat = re.compile(r"(async def create_key_ui\(.*?await db\.commit\(\)\n)", re.S)
src, n = pat.subn(lambda m: m.group(1) + '    if await bind_key_to_servers(key_id): print(f"[3b-i] bind warning: {key_id}")\n', src, count=1)
assert n == 1, 'op1 failed'

# --- op2: create_key_for_client -> bind after commit ---
old = '    await db.commit()\n    return RedirectResponse(f"/clients/{client_id}?msg=key_created", status_code=303)'
new = ('    await db.commit()\n'
       '    bind_msg = await bind_key_to_servers(key_id)\n'
       '    return RedirectResponse(f"/clients/{client_id}?msg=key_created{bind_msg}", status_code=303)')
assert src.count(old) == 1, 'op2 anchor not unique/found'
src = src.replace(old, new, 1)

# --- op3: download -> v2 renderer with v1 fallback ---
i = src.index('@app.get("/keys/{key_id}/download")')
j = src.index('# === Client (device) management (web UI) ===')
NEW_DOWNLOAD = '''@app.get("/keys/{key_id}/download")
async def download_key_conf(request: Request, key_id: str):
    """Скачать ключ: Key File v2 (MANIFEST §5) если есть биндинги, иначе legacy v1."""
    import hashlib
    import json as _json
    from fastapi.responses import Response
    from .core.secrets import decrypt as _dec

    key = await db.fetchone("SELECT * FROM access_keys WHERE key_id = ?", key_id)
    if not key:
        raise HTTPException(status_code=404, detail="Key not found")

    rows = await db.fetchall(
        "SELECT server_id, protocol, client_config_enc FROM key_server_clients WHERE key_id = ?",
        key_id,
    )
    peers = []
    for r in rows or []:
        try:
            cfg = _json.loads(_dec(r["client_config_enc"], r["protocol"]))
        except Exception:
            continue
        peers.append((r["protocol"], r["server_id"], cfg))

    if peers:
        client = None
        if key["client_id"]:
            client = await db.fetchone(
                "SELECT display_name FROM clients WHERE id = ?", key["client_id"])
        content = render_keyfile_v2(
            key, client["display_name"] if client else "Client", peers)
        filename = f"hydra-key-{key_id[:8]}.conf"
        return Response(
            content=content, media_type="text/plain",
            headers={"Content-Disposition": f'attachment; filename="{filename}"'})

    # Legacy Hydra Key File v1 (docs/KEYFILE.md)
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
    filename = f"hydra-key-{key_id[:8]}.conf"
    return Response(
        content=content, media_type="text/plain",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'})


'''
src = src[:i] + NEW_DOWNLOAD + src[j:]

# --- op4: helpers at EOF ---
HELPERS = '''

# === Этап 3b-i: выпуск Key File v2 (MANIFEST §5, Р-23) ===

BIND_PROTOCOLS = ("awg", "aivpn", "wdtt")  # 3b-i: AWG; 3b-ii добавит aivpn (Р-24)


def _sanitize_label(raw: str) -> str:
    import re as _re
    s = _re.sub(r"[^A-Za-z0-9\\-_.]", "-", (raw or "").strip())
    return (s or "client")[:32]


async def bind_key_to_servers(key_id: str) -> str:
    """Привязать ключ к активным protocol_instances: add_client + Fernet-конфиг.

    Возвращает суффикс для UI-сообщения ("" = успех, "&bind_warn=1" = частично).
    """
    import json as _json
    from .core.secrets import encrypt as _enc

    key = await db.fetchone("SELECT * FROM access_keys WHERE key_id = ?", key_id)
    if not key:
        return "&bind_warn=1"

    client = None
    if key["client_id"]:
        client = await db.fetchone(
            "SELECT display_name FROM clients WHERE id = ?", key["client_id"])
    label = _sanitize_label(
        (client["display_name"] if client else "client") + "-" + key_id[:6])

    placeholders = ",".join("?" * len(BIND_PROTOCOLS))
    instances = await db.fetchall(
        f"SELECT server_id, protocol FROM protocol_instances "
        f"WHERE status='active' AND protocol IN ({placeholders})",
        *BIND_PROTOCOLS,
    )

    warn = ""
    for inst in instances or []:
        try:
            res = await manager.add_client_to_server(
                inst["server_id"], inst["protocol"], label)
            blob = _enc(_json.dumps(res["config_data"]), inst["protocol"])
            await db.execute(
                "INSERT OR REPLACE INTO key_server_clients "
                "(key_id, server_id, protocol, client_config_enc, created_at) "
                "VALUES (?,?,?,?,datetime('now'))",
                key_id, inst["server_id"], inst["protocol"], blob,
            )
        except Exception as e:
            print(f"[3b-i] bind failed {key_id[:8]} {inst['server_id']}: {e}")
            warn = "&bind_warn=1"
    await db.commit()
    return warn


def render_keyfile_v2(key: dict, client_name: str, peers: list) -> str:
    """Рендер Key File v2: [Hydra] + [Peer.<protocol>.<server_id>]."""
    expires = (key["expires_at"] or "never").replace("T", " ")
    lines = [
        "# Hydra Key File v2",
        "# Формат: docs/KEYFILE_V2.md (MANIFEST §5)",
        "",
        "[Hydra]",
        "Version = 2",
        f"KeyId = {key['key_id']}",
        f"ExpiresAt = {expires}",
        f"MaxDevices = {key['max_devices']}",
        f"ClientName = {client_name}",
    ]
    for proto, server_id, cfg in peers:
        lines.append("")
        lines.append(f"[Peer.{proto}.{server_id}]")
        lines.append(f"Endpoint = {cfg['endpoint']}")
        lines.append(f"Label = \\"{cfg.get('label', '')}\\"")
        if proto == "awg":
            lines.append(f"PublicKey = {cfg['server_pub']}")
            lines.append(f"PrivateKey = {cfg['private_key']}")
            lines.append(f"Address = {cfg['ip']}/32")
            lines.append("DNS = 1.1.1.1")
            ob = cfg.get("obfuscation", {})
            for k in ("Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4"):
                v = ob.get(k, "")
                if v and v != "0":
                    lines.append(f"{k} = {v}")
            if ob.get("HeaderProtectionKey"):
                lines.append(f"HeaderProtectionKey = {ob['HeaderProtectionKey']}")
    return "\\n".join(lines) + "\\n"
'''
src += HELPERS

open(PATH, 'w', encoding='utf-8').write(src)
print('✓ panel.py пропатчен (backup: hydra/panel.py.bak3b)')
