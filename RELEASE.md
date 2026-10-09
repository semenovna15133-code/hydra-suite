# Релизы клиента Hydra

Канал: GitHub Releases `semenovna15133-code/hydra-suite` (публичный).

## Имена ассетов (уже сложившиеся)
- `hydra-client-<X.Y.Z>-arm32.apk`
- `hydra-client-<X.Y.Z>-arm64.apk`
- `hydra-client-<X.Y.Z>-universal.apk`  ← обязателен: fallback для эмуляторов и редких ABI

Префикс `hydra-client-` обязателен: клиент игнорирует прочие файлы релиза.
ABI выбирается по устройству (arm64-v8a→arm64, armeabi-v7a→arm32), иначе universal.

## Порядок выпуска
1. Поднять `version:` в `client/flutter_app/pubspec.yaml` (`X.Y.Z+N`).
2. Собрать APK по ABI + universal, назвать по правилу выше.
3. Создать релиз с тегом `v<X.Y.Z>`, приложить ассеты + changelog в теле.
4. Клиенты при следующем входе получат диалог «Доступна версия X.Y.Z».
