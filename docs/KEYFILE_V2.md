# Hydra Key File v2 (.conf)

Контракт панель ↔ клиент (MANIFEST §5). INI-совместимый формат.
Генерация: панель `/keys/{id}/download` после bind ключа к серверам
(`bind_key_to_servers`, таблица `key_server_clients`, Fernet-конфиги).
Доставка: бот (Этап 4) / вручную админом (dev-путь Р-23).

## Пример

    # Hydra Key File v2
    [Hydra]
    Version = 2
    KeyId = XJOS6tFlLwBFH3asKL04XCd4XVXvskz3KmrDhZpc_Bg
    ExpiresAt = 2027-01-01 00:00:00
    MaxDevices = 3
    ClientName = Ivan

    [Peer.awg.fi-polygon]
    Endpoint = 203.0.113.10:51820
    Label = "Finland · Polygon"
    PublicKey = <base64 server public key>
    PrivateKey = <base64 client private key>
    Address = 10.0.1.2/32
    DNS = 1.1.1.1
    Jc = 8
    Jmin = 50
    Jmax = 1000
    HeaderProtectionKey = <base64>

## Секция [Hydra]

| Поле | Тип | Описание |
|---|---|---|
| Version | int | Версия формата (2) |
| KeyId | str | access_keys.key_id |
| ExpiresAt | str | 'YYYY-MM-DD HH:MM:SS' или 'never' |
| MaxDevices | int | Лимит устройств (enforcement на сервере, Б-04) |
| ClientName | str | display_name клиента (UI мультипрофиля) |

## Секция [Peer.<protocol>.<server_id>]

Общие поля: `Endpoint` (host:port), `Label` (человеческое имя, в кавычках).

### awg (AmneziaWG 3.1)
| Поле | Описание |
|---|---|
| PublicKey | публичный ключ СЕРВЕРА |
| PrivateKey | приватный ключ КЛИЕНТА |
| Address | VPN-IP клиента (/32) |
| DNS | DNS внутри туннеля |
| Jc, Jmin, Jmax | базовые jitter-параметры |
| S1..S4 | size-параметры (пишутся если != 0) |
| HeaderProtectionKey | base64, если назначен |

Резерв (панель выдаёт по мере конфигурирования, PASSPORT «AWG 3.1 из UI»):
I1-I5, H1-H4, ContentPaddingAddition, RandomTrailers, DisableCookies,
RekeyAfterTime, RekeyTimeout, RejectAfterTime, KeepaliveTimeout,
MaxHandshakeAttempts, MTU, ListenPort.

### wdtt (Фаза 3c, Р-24 — не в MVP)
Password, VKHashes (4 строки), Streams.

### aivpn (3b-ii)
Key = aivpn://<base64url(JSON s,k,p,i,n)>

## Поведение клиента

1. Парсить `[Hydra]`: Version == 2, иначе отказ (v1 → legacy по К-01)
2. Требовать >= 1 секцию `[Peer.*]` (К-05)
3. Секреты в app-specific storage (К-04, S-04); права файла 600
4. 3b-i: подключение к первому awg-peer; далее Smart Connect (Этап 3c)

## Правила

К-01..К-05 — см. MANIFEST §5. Ключ самодостаточен (К-02): клиент
не обращается к панели и не хранит её адрес.
