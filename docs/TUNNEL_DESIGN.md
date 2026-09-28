# Hydra Client — архитектура VPN-туннеля (черновик, Этап 3b-i)

Статус: РЕАЛИЗОВАНО (2026-09-28). End-to-end подтверждён.
Ограничения-источники: MANIFEST §3 (таблица протоколов), Р-03, Р-11, 6.4, 7.5.

## Целевая схема (Р-03)

    Android VpnService (TUN fd)
            │  IP-пакеты
            ▼
    Go netstack (gVisor) ── tun2socks-логика НАША, в Go
            │  TCP/UDP-потоки
            ▼
    SOCKS5 127.0.0.1:<port>  ← апстрим активного бэкенда
            ▲
            │
    Бэкенд-провайдер:
      awg:   amneziawg-go device + netstack → свой SOCKS5-мост
      aivpn: Rust .so (JNI) → SOCKS5 (--proxy-listen)   [3b-ii]
      wdtt:  libclient.so → SOCKS5 127.0.0.1:1080       [3c]

Failover (7.5): меняем SOCKS5-апстрим ПОД живым TUN — ОС не видит разрыва.

## Компоненты (порядок реализации 3b-i)

1. HydraVpnService (Kotlin): разрешение VPN, Builder (address из ключа,
   DNS из ключа, MTU 1420), foreground-уведомление (Р-11), protect() сокетов
2. Go awg-бэкенд: amneziawg-go device с параметрами из ключа
   (PrivateKey/PublicKey/Endpoint/Jc..HPKey) + netstack → SOCKS5 на 127.0.0.1
3. Go TUN-мост: приём fd из VpnService (VpnService.Builder.establish() →
   ParcelFileDescriptor → Go), netstack stack, маршрутизация в SOCKS5
4. UI: кнопка «Подключить» на карточке ключа / статус-экран (9.1 AUTO позже, в 3c)
5. E2E-тест: эмулятор → fi-polygon:51820, проверка трафика (curl через туннель)

## Открытые вопросы (обсудить ДО реализации)

В-01  amneziawg-go: версия/форк (github.com/amnezia-vpn/amneziawg-go),
      лицензия, совместимость с Go 1.26 и gomobile-сборкой
В-02  UDP через SOCKS5: UDP ASSOCIATE в нашем tun2socks-мосте (awg-трафик
      клиента внутри туннеля — UDP; без ASSOCIATE DNS/QUIC умрут)
В-03  protect(): какие сокеты защищать от попадания в туннель
      (UDP-сокет awg-бэкенда, SOCKS5 loopback не защищаем)
В-04  DNS: addDnsServer(ключ.DNS) в Builder vs DNS-перехват в netstack
В-05  Эмулятор x86_64: UDP-исходящие на 31.77.202.131:51820 — проверить
      доступность до начала реализации (иначе тест только на реальном device)
В-06  Foreground service: тип уведомления specialUse vs dataSync (Android 14+)
В-07  Разделение APK-архитектур: amneziawg-go в том же AAR (keyfile.aar)
      или отдельный модуль tunnel.aar (чистота границ, время сборки)

## ВНЕ объема 3b-i (не делать сейчас)

- Smart Connect / probe / score (Этап 3c)
- Split tunneling (3c), статистика (3d), WDTT (3c), AIVPN (3b-ii)
- Kill Switch, IPv6-настройки (9.4 — polish)

## Подтверждение end-to-end (2026-09-28)

- client ping 8.8.8.8 через туннель: 4/4, 0% loss, ~65ms
- server: latest handshake 35s ago, 3.52 KiB received
- iptables FORWARD awg0->ens3: 13 packets forwarded
- Полное описание решённых проблем Р-01..Р-04: git log 223975c
