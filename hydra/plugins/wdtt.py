"""WDTT Plus v17 plugin for Hydra Panel."""
import json
from typing import Dict, Any
from ..core.protocol import ProtocolPlugin, ClientConfig, PluginResult
from ..ssh import SSHTransport


class WDTTPlugin(ProtocolPlugin):
    """WDTT Plus v17 implementation."""
    
    @property
    def name(self) -> str:
        return "wdtt"
    
    @property
    def version(self) -> str:
        return "17"
    
    async def install(self, server_ip: str, ssh_key_path: str, max_passwords: int = 50) -> PluginResult:
        """Install WDTT v17 on remote server.
        
        Steps:
        1. Check if already installed (systemctl is-active wdtt)
        2. Install Go
        3. Clone WDTT-Plus repo
        4. Build wdtt-server
        5. Generate main password
        6. Run install.sh init-config + install
        7. Open ports 56000, 56001 (NOT 9000 - that's client-side only)
        """
        if not (1 <= max_passwords <= 10000):
            raise ValueError(f"max_passwords must be in [1, 10000], got: {max_passwords}")
        
        cmd = f'''
set -euo pipefail

# Проверка что уже установлен
if systemctl is-active wdtt >/dev/null 2>&1; then
    echo "ALREADY_INSTALLED"
    exit 0
fi

echo "Installing WDTT Plus v17..."

# Установка Go
GO_VER=$(curl -s "https://go.dev/VERSION?m=text" | head -1)
if [ ! -d /usr/local/go ]; then
    wget -q "https://go.dev/dl/${{GO_VER}}.linux-amd64.tar.gz"
    sudo tar -C /usr/local -xzf "${{GO_VER}}.linux-amd64.tar.gz"
    rm -f "${{GO_VER}}.linux-amd64.tar.gz"
fi
echo 'export PATH=$PATH:/usr/local/go/bin' | sudo tee /etc/profile.d/go.sh
sudo chmod +x /etc/profile.d/go.sh
export PATH=$PATH:/usr/local/go/bin

# Клонирование репо
cd /root
if [ ! -d WDTT-Plus ]; then
    git clone https://github.com/Ivan4537/WDTT-Plus.git
fi
cd WDTT-Plus

# Сборка
go build -o wdtt-server .

# Генерация main password
openssl rand -base64 32 | tr -d '\\n' | sudo tee /root/wdtt-main.pass > /dev/null
sudo chmod 600 /root/wdtt-main.pass

# Установка через install.sh
cd server-installer
sudo bash install.sh init-config \\
  --output /root/wdtt-initial.json \\
  --password-file /root/wdtt-main.pass \\
  --dtls-port 56000 --wg-port 56001 --client-port 9000 \\
  --dns 1.1.1.1 --max-passwords {max_passwords} --yes

sudo bash install.sh install \\
  --binary /root/WDTT-Plus/wdtt-server \\
  --config /root/wdtt-initial.json \\
  --dtls-port 56000 --wg-port 56001 --client-port 9000 \\
  --dns 1.1.1.1 --max-passwords {max_passwords} \\
  --wg-backend kernel --firewall open --yes

# Открытие портов (9000 НЕ открываем — это локальный порт клиента)
sudo ufw allow 56000/udp
sudo ufw allow 56001/udp

echo "INSTALL_COMPLETE"
'''
        
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            result = await ssh.run(cmd)
            output = result.stdout
            
            if "ALREADY_INSTALLED" in output:
                return PluginResult(
                    success=True,
                    message="WDTT already installed",
                )
            
            if not result.success or "ERROR" in output:
                return PluginResult(
                    success=False,
                    message=f"Installation failed: {output}\nStderr: {result.stderr}",
                )
            
            if "INSTALL_COMPLETE" in output:
                return PluginResult(
                    success=True,
                    message=f"WDTT v17 installed successfully (max_passwords={max_passwords})",
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
        """Add a new WDTT client (safe: run_with_stdin, no shell interpolation).
        
        Security (Р-26): user input (label) passed to remote command via
        JSON stdin, not via shell interpolation. Whitelist validation on label.
        """
        label = kwargs.get("label", "hydra-client")
        days = int(kwargs.get("days", 0))
        
        # Whitelist validation (Р-26): alphanumeric + dash + underscore + dot
        if not all(c.isalnum() or c in "-_." for c in label):
            raise ValueError(f"Invalid label: {label}")
        
        if not (0 <= days <= 36500):
            raise ValueError(f"days must be in [0, 36500], got {days}")
        
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            # Step 1: get main password separately
            pw_result = await ssh.run("cat /root/wdtt-main.pass 2>/dev/null || echo ''")
            main_pw = pw_result.stdout.strip()
            if not main_pw:
                raise RuntimeError("wdtt-main.pass not found or empty on server")
            
            # Step 2: build JSON safely (no shell interpolation)
            req_dict = {
                "main_password": main_pw,
                "args": ["create", "--days", str(days), "--label", label]
            }
            req_json = json.dumps(req_dict)
            
            # Step 3: pass JSON via stdin (Р-26: run_with_stdin is safe)
            result = await ssh.run_with_stdin(
                "/usr/local/bin/wdtt-server admin --config-dir /etc/wdtt --request-stdin 2>/dev/null",
                stdin_data=req_json
            )
            
            try:
                data = json.loads(result.stdout)
            except json.JSONDecodeError as e:
                raise RuntimeError(f"Failed to parse WDTT response: {e}")
            
            if "error" in data:
                raise RuntimeError(f"WDTT error: {data['error']}")
            
            password_obj = data.get("password")
            if not password_obj or "password" not in password_obj:
                raise RuntimeError(f"Unexpected WDTT response: {data}")
            
            password = password_obj["password"]
            connection_string = f"wdtt://{server_ip}:56000?password={password}"
            
            return ClientConfig(
                protocol="wdtt",
                client_id=password,
                config_data={
                    "label": label,
                    "password": password,
                    "days": days,
                    "is_deactivated": password_obj.get("is_deactivated"),
                    "expires_at": password_obj.get("expires_at"),
                },
                connection_string=connection_string,
            )
    
    async def list_clients(self, server_ip: str, ssh_key_path: str) -> list[Dict[str, Any]]:
        """List all WDTT clients (safe: run_with_stdin).
        
        Security (Р-26): no shell interpolation, main_pw passed via JSON stdin.
        """
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            # Step 1: get main password separately
            pw_result = await ssh.run("cat /root/wdtt-main.pass 2>/dev/null || echo ''")
            main_pw = pw_result.stdout.strip()
            if not main_pw:
                return []
            
            # Step 2: build JSON safely
            req_dict = {
                "main_password": main_pw,
                "args": ["list"]
            }
            req_json = json.dumps(req_dict)
            
            # Step 3: pass JSON via stdin (Р-26)
            result = await ssh.run_with_stdin(
                "/usr/local/bin/wdtt-server admin --config-dir /etc/wdtt --request-stdin 2>/dev/null",
                stdin_data=req_json
            )
            
            try:
                data = json.loads(result.stdout)
            except json.JSONDecodeError as e:
                raise RuntimeError(f"Failed to parse: {e}")
            
            if "error" in data:
                raise RuntimeError(f"WDTT error: {data['error']}")
            
            passwords = data.get("passwords", [])
            return [
                {
                    "protocol": "wdtt",
                    "client_id": p.get("password"),
                    "label": p.get("label", ""),
                    "is_deactivated": p.get("is_deactivated"),
                    "expires_at": p.get("expires_at"),
                    "active": p.get("is_deactivated") is None,
                }
                for p in passwords
            ]
    
    async def remove_client(
        self,
        server_ip: str,
        ssh_key_path: str,
        client_id: str
    ) -> PluginResult:
        """Remove a WDTT client (safe: run_with_stdin, no shell interpolation).
        
        Security (Р-26): client_id passed via JSON stdin, not shell interpolation.
        """
        # Strict format validation: WDTT password is 16 chars
        if len(client_id) != 16 or not client_id.isprintable():
            raise ValueError(f"client_id must be 16-char password, got: {client_id!r}")
        
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            # Step 1: get main password separately
            pw_result = await ssh.run("cat /root/wdtt-main.pass 2>/dev/null || echo ''")
            main_pw = pw_result.stdout.strip()
            if not main_pw:
                raise RuntimeError("wdtt-main.pass not found or empty on server")
            
            # Step 2: build JSON safely
            req_dict = {
                "main_password": main_pw,
                "args": ["delete", "--password", client_id]
            }
            req_json = json.dumps(req_dict)
            
            # Step 3: pass JSON via stdin (Р-26)
            result = await ssh.run_with_stdin(
                "/usr/local/bin/wdtt-server admin --config-dir /etc/wdtt --request-stdin 2>/dev/null",
                stdin_data=req_json
            )
            
            try:
                data = json.loads(result.stdout)
            except json.JSONDecodeError as e:
                raise RuntimeError(f"Failed to parse: {e}")
            
            if "error" in data:
                return PluginResult(success=False, message=f"WDTT error: {data['error']}")
            
            return PluginResult(success=True, message=f"Client {client_id} removed")
    
    async def get_status(self, server_ip: str, ssh_key_path: str) -> Dict[str, Any]:
        """Get WDTT status via SSH (safe: run_with_stdin).
        
        Security (Р-26): no shell interpolation, main_pw passed via JSON stdin.
        """
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            status_result = await ssh.run("systemctl is-active wdtt 2>/dev/null || echo 'inactive'")
            service_status = status_result.stdout.strip()
            
            # Step 1: get main password separately
            pw_result = await ssh.run("cat /root/wdtt-main.pass 2>/dev/null || echo ''")
            main_pw = pw_result.stdout.strip()
            
            if not main_pw:
                clients_result = SSHResult(stdout='{"error": "password file not found"}', stderr='', exit_code=0)
            else:
                # Step 2: build JSON safely
                req_dict = {
                    "main_password": main_pw,
                    "args": ["list"]
                }
                req_json = json.dumps(req_dict)
                
                # Step 3: pass JSON via stdin (Р-26)
                clients_result = await ssh.run_with_stdin(
                    "/usr/local/bin/wdtt-server admin --config-dir /etc/wdtt --request-stdin 2>/dev/null || echo '{\"error\": \"admin command failed\"}'",
                    stdin_data=req_json
                )
            
            try:
                clients_data = json.loads(clients_result.stdout)
                if "error" in clients_data:
                    client_count = 0
                    error_msg = clients_data["error"]
                else:
                    passwords = clients_data.get("passwords", [])
                    client_count = sum(1 for p in passwords if p.get("is_deactivated") is None)
                    error_msg = None
            except (json.JSONDecodeError, KeyError) as e:
                client_count = 0
                error_msg = f"Failed to parse: {e}"
            
            return {
                "service_status": service_status,
                "active_clients": client_count,
                "error": error_msg,
            }
