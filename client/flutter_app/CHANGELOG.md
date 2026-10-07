
## 2026-10-07 — WDTT: обход SELinux permission denied
- Добавлен флаг `-turn-stream-first` для использования TCP/TLS вместо UDP
- WDTT получает TURN credentials через VKCalls (modern-vkcalls)
- TURN серверы: 193.203.43.42:19302, 193.203.43.4:19302
- 9 воркеров распределены по группе
- Hydra-bridge получил TUN fd, tun2socks FULL-DUPLEX running
- Нормализация VK-хешей: из ссылки vk.ru/call/join/XXX извлекается только XXX
- Правильные флаги: одинарные дефисы (-mode, -vk, -peer, -password, -n)
