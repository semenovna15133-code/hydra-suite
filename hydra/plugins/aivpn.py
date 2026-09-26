"""AIVPN v1.1.0 plugin for Hydra Panel."""
import re
from typing import Dict, Any
from ..core.protocol import ProtocolPlugin, ClientConfig, PluginResult
from ..ssh import SSHTransport

CLI = "/usr/local/bin/aivpn-server"
KEY_FILE = "/etc/aivpn/server.key"
CLIENTS_DB = "/etc/aivpn/clients.json"
ID_REGEX = re.compile(r'^[0-9a-fA-F]{16}$')


class AIVPNPlugin(ProtocolPlugin):
    """AIVPN v1.1.0 implementation."""
    
    @property
    def name(self) -> str:
        return "aivpn"
    
    @property
    def version(self) -> str:
        return "1.1.0"
    
    async def install(self, server_ip: str, ssh_key_path: str) -> PluginResult:
        """Install AIVPN v1.1.0 on remote server.
        
        Steps:
        1. Check if already installed (systemctl is-active aivpn-server)
        2. Clone aivpn repo
        3. Run install-server.sh --mode systemd --port 443
        4. Patch systemd unit for CAP_NET_BIND_SERVICE
        5. Restart service
        """
        cmd = '''
set -euo pipefail

# Проверка что уже установлен
if systemctl is-active aivpn-server >/dev/null 2>&1; then
    echo "ALREADY_INSTALLED"
    exit 0
fi

echo "Installing AIVPN v1.1.0..."

# Клонирование репо
cd /root
if [ ! -d aivpn ]; then
    git clone https://github.com/infosave2007/aivpn.git
fi
cd aivpn/deploy

# Установка через install-server.sh
sudo bash install-server.sh --mode systemd --port 443

# Патч systemd unit для CAP_NET_BIND_SERVICE
UNIT_FILE=/etc/systemd/system/aivpn-server.service
sudo cp "$UNIT_FILE" "${UNIT_FILE}.bak"
sudo sed -i 's/^CapabilityBoundingSet=CAP_NET_ADMIN$/CapabilityBoundingSet=CAP_NET_ADMIN CAP_NET_BIND_SERVICE/; s/^AmbientCapabilities=CAP_NET_ADMIN$/AmbientCapabilities=CAP_NET_ADMIN CAP_NET_BIND_SERVICE/' "$UNIT_FILE"

# Проверка что патч применился
if ! grep -q "CAP_NET_ADMIN CAP_NET_BIND_SERVICE" "$UNIT_FILE"; then
    echo "ERROR: sed-patch failed"
    sudo mv "${UNIT_FILE}.bak" "$UNIT_FILE"
    exit 1
fi
rm -f "${UNIT_FILE}.bak"

# Перезапуск сервиса
sudo systemctl daemon-reload
sudo systemctl restart aivpn-server

# Ожидание старта
sleep 2
if ! systemctl is-active aivpn-server >/dev/null 2>&1; then
    echo "ERROR: service failed to start"
    sudo journalctl -u aivpn-server --no-pager -n 20
    exit 1
fi

echo "INSTALL_COMPLETE"
'''
        
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            result = await ssh.run(cmd)
            output = result.stdout
            
            if "ALREADY_INSTALLED" in output:
                return PluginResult(
                    success=True,
                    message="AIVPN already installed",
                )
            
            if not result.success or "ERROR" in output:
                return PluginResult(
                    success=False,
                    message=f"Installation failed: {output}\nStderr: {result.stderr}",
                )
            
            if "INSTALL_COMPLETE" in output:
                return PluginResult(
                    success=True,
                    message="AIVPN v1.1.0 installed successfully",
                )
            
            return PluginResult(
                success=False,
                message=f"Unknown result: {output}",
            )
    
    async def add_client(
        self,
        server_ip: str,
        ssh_key_path: str,
        client_id: str,
        **kwargs
    ) -> ClientConfig:
        """Add a new AIVPN client (safe: env var + whitelist validation).
        
        Security (Р-26): label passed via env var CLIENT_LABEL, not direct
        shell interpolation. Whitelist validation on input.
        """
        label = client_id or "hydra-client"
        role = kwargs.get("role", "user")
        
        if not all(c.isalnum() or c in "-_." for c in label):
            raise ValueError(f"Invalid label: {label}")
        
        cmd = f'''
set -euo pipefail
export CLIENT_LABEL="{label}"
{CLI} --add-client "$CLIENT_LABEL" \\
  --server-ip {server_ip}:443 \\
  --key-file {KEY_FILE} \\
  --clients-db {CLIENTS_DB} 2>&1
'''
        
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            result = await ssh.run(cmd)
            output = result.stdout
            
            if not result.success or "ERROR" in output.upper():
                raise RuntimeError(f"AIVPN add_client failed: {output}")
            
            id_match = re.search(r'ID:\s*([0-9a-fA-F]{16})', output)
            url_match = re.search(r'(aivpn://[^\s]+)', output)
            
            if not id_match:
                raise RuntimeError(f"Cannot find ID in output: {output}")
            
            aivpn_id = id_match.group(1)
            aivpn_url = url_match.group(1) if url_match else None
            
            return ClientConfig(
                protocol="aivpn",
                client_id=aivpn_id,
                config_data={
                    "label": label,
                    "role": role,
                    "aivpn_url": aivpn_url,
                },
                connection_string=aivpn_url,
            )
    
    async def list_clients(self, server_ip: str, ssh_key_path: str) -> list[Dict[str, Any]]:
        """List all AIVPN clients."""
        cmd = f'''
{CLI} --list-clients \\
  --key-file {KEY_FILE} \\
  --clients-db {CLIENTS_DB} 2>/dev/null
'''
        
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            result = await ssh.run(cmd)
            output = result.stdout
            
            clients = []
            for line in output.strip().split('\n'):
                line = line.strip()
                if not line:
                    continue
                if line.startswith('-') or line.startswith('='):
                    continue
                if 'ID' in line and 'NAME' in line:
                    continue
                
                parts = line.split()
                if len(parts) < 2:
                    continue
                
                potential_id = parts[0]
                if ID_REGEX.match(potential_id):
                    clients.append({
                        "protocol": "aivpn",
                        "client_id": potential_id,
                        "label": parts[1] if len(parts) > 1 else "",
                        "status": parts[2] if len(parts) > 2 else "",
                        "active": True,
                    })
            
            return clients
    
    async def remove_client(
        self,
        server_ip: str,
        ssh_key_path: str,
        client_id: str
    ) -> PluginResult:
        """Remove an AIVPN client (safe: env var + whitelist validation).
        
        Security (Р-26): client_id passed via env var CLIENT_ID, not direct
        shell interpolation. Whitelist regex validation on input.
        """
        if not ID_REGEX.match(client_id):
            raise ValueError(f"client_id must be 16-hex ID, got: {client_id!r}")
        
        cmd = f'''
set -euo pipefail
export CLIENT_ID="{client_id}"
{CLI} --remove-client "$CLIENT_ID" \\
  --key-file {KEY_FILE} \\
  --clients-db {CLIENTS_DB} 2>&1
'''
        
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            result = await ssh.run(cmd)
            
            if not result.success:
                return PluginResult(
                    success=False,
                    message=f"Remove failed: {result.stdout or result.stderr}",
                )
            
            return PluginResult(
                success=True,
                message=f"Client {client_id} removed",
            )
    
    async def get_status(self, server_ip: str, ssh_key_path: str) -> Dict[str, Any]:
        """Get AIVPN status via SSH."""
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            status_result = await ssh.run("systemctl is-active aivpn-server 2>/dev/null || echo 'inactive'")
            service_status = status_result.stdout.strip()
            
            cmd = f'''
{CLI} --list-clients \\
  --key-file {KEY_FILE} \\
  --clients-db {CLIENTS_DB} 2>/dev/null || \\
  echo "ERROR: command failed"
'''
            
            clients_result = await ssh.run(cmd)
            
            if "ERROR:" in clients_result.stdout:
                client_count = 0
                error_msg = clients_result.stdout
            else:
                lines = clients_result.stdout.strip().split('\n')
                client_lines = [
                    line for line in lines
                    if line.strip()
                    and not line.startswith('-')
                    and not line.startswith('=')
                    and 'ID' not in line
                    and 'NAME' not in line
                ]
                client_count = len(client_lines)
                error_msg = None
            
            return {
                "service_status": service_status,
                "active_clients": client_count,
                "error": error_msg,
            }
