"""SSH transport layer for Hydra Panel using asyncssh with security hardening."""
from pathlib import Path
from typing import Optional
import asyncssh
import os


class SecurityError(Exception):
    """Security-related error (fail-fast)."""
    pass


class SSHResult:
    """Result of SSH command execution."""
    
    def __init__(self, stdout: str, stderr: str, exit_code: int):
        self.stdout = stdout
        self.stderr = stderr
        self.exit_code = exit_code
    
    @property
    def success(self) -> bool:
        return self.exit_code == 0
    
    def __str__(self) -> str:
        return f"SSHResult(exit={self.exit_code}, stdout={self.stdout!r}, stderr={self.stderr!r})"


class SSHTransport:
    """Async SSH transport with proper host key verification.
    
    Security features (Этап 2.5):
    - fail-fast SecurityError if known_hosts missing (unless skip_host_key_check=True)
    - run_with_stdin() for safe JSON passing (no shell interpolation)
    - Emergency skip only for bootstrap with password in _ssh_run_once
    """
    
    def __init__(
        self,
        host: str,
        username: str = "root",
        key_path: Optional[str] = None,
        password: Optional[str] = None,  # For bootstrap only
        connect_timeout: int = 30,
        known_hosts_path: Optional[str] = None,
        skip_host_key_check: bool = False,  # Emergency-only
    ):
        self.host = host
        self.username = username
        self.key_path = key_path
        self.password = password
        self.connect_timeout = connect_timeout
        self.skip_host_key_check = skip_host_key_check
        
        # Load known_hosts from config or default locations
        if known_hosts_path:
            self.known_hosts_path = Path(known_hosts_path).expanduser()
        else:
            default_path = Path.home() / ".hydra" / "known_hosts"
            if not default_path.exists():
                default_path = Path.home() / ".ssh" / "known_hosts"
            self.known_hosts_path = default_path
        
        self._conn: Optional[asyncssh.SSHClientConnection] = None
    
    async def connect(self) -> None:
        """Establish SSH connection with host key verification."""
        if self._conn is not None:
            return
        
        connect_args = {
            "host": self.host,
            "username": self.username,
            "connect_timeout": self.connect_timeout,
        }
        
        # Host key verification (fail-fast unless emergency skip)
        if not self.skip_host_key_check:
            if not self.known_hosts_path.exists():
                raise SecurityError(
                    f"SSH known_hosts file not found at {self.known_hosts_path}. "
                    f"Create it or set ssh_skip_host_key_check=true in panel.yaml "
                    f"(emergency-only). Cannot connect to {self.host} safely."
                )
            connect_args["known_hosts"] = str(self.known_hosts_path)
        
        # Client key (preferred over password)
        if self.key_path:
            key_path = Path(self.key_path).expanduser()
            if not key_path.exists():
                raise FileNotFoundError(f"SSH key not found: {key_path}")
            connect_args["client_keys"] = [str(key_path)]
        elif self.password:
            connect_args["password"] = self.password
        
        try:
            self._conn = await asyncssh.connect(**connect_args)
        except asyncssh.Error as e:
            raise ConnectionError(f"SSH connection failed to {self.host}: {e}") from e
    
    async def run(self, command: str, check: bool = False) -> SSHResult:
        """Execute command and return result."""
        if self._conn is None:
            await self.connect()
        
        try:
            result = await self._conn.run(command, check=False)
            ssh_result = SSHResult(
                stdout=result.stdout,
                stderr=result.stderr,
                exit_code=result.exit_status or 0,
            )
        except asyncssh.Error as e:
            ssh_result = SSHResult(stdout="", stderr=str(e), exit_code=-1)
        
        if check and not ssh_result.success:
            raise RuntimeError(
                f"Command failed on {self.host}: {command}\n"
                f"Exit code: {ssh_result.exit_code}\n"
                f"Stderr: {ssh_result.stderr}"
            )
        
        return ssh_result
    
    async def run_with_stdin(
        self,
        command: str,
        stdin_data: str,
        check: bool = False
    ) -> SSHResult:
        """Execute command with stdin data (safe for JSON, no shell interpolation).
        
        This method passes stdin_data directly to the command's stdin via
        asyncssh, avoiding shell interpolation entirely. Use this for
        passing user input (label, client_id, JSON payloads) to remote
        commands.
        
        Security (Р-26): this is the safe way to pass user input to plugins.
        Never use shell interpolation like `echo "{user_input}" | cmd`.
        """
        if self._conn is None:
            await self.connect()
        
        try:
            result = await self._conn.run(
                command,
                input=stdin_data,  # asyncssh passes this directly to stdin
                check=False
            )
            ssh_result = SSHResult(
                stdout=result.stdout,
                stderr=result.stderr,
                exit_code=result.exit_status or 0,
            )
        except asyncssh.Error as e:
            ssh_result = SSHResult(stdout="", stderr=str(e), exit_code=-1)
        
        if check and not ssh_result.success:
            raise RuntimeError(
                f"Command failed on {self.host}: {command}\n"
                f"Exit code: {ssh_result.exit_code}\n"
                f"Stderr: {ssh_result.stderr}"
            )
        
        return ssh_result
    
    async def __aenter__(self):
        await self.connect()
        return self
    
    async def __aexit__(self, exc_type, exc_val, exc_tb):
        if self._conn:
            self._conn.close()
            self._conn = None
