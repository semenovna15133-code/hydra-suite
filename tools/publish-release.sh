#!/bin/bash
set -e

# Безопасный скрипт публикации релиза Hydra
# Требует: export GITHUB_TOKEN=ghp_... в окружении (НЕ в git/APK)

REPO="semenovna15133-code/hydra-suite"
TAG="${1:-v0.3.0}"
NAME="${2:-Hydra Client 0.3.0}"
RELEASE_DIR="release/${TAG}"

if [ -z "$GITHUB_TOKEN" ]; then
  echo "✗ GITHUB_TOKEN не установлен в окружении"
  echo "  Сделай: export GITHUB_TOKEN=ghp_..."
  exit 1
fi

if [ ! -d "$RELEASE_DIR" ]; then
  echo "✗ Директория $RELEASE_DIR не найдена"
  exit 1
fi

cd "$(dirname "$0")/.."
echo "→ Публикую $TAG в $REPO"

# Проверяем, нет ли уже такого тега
if git ls-remote --tags origin | grep -q "refs/tags/${TAG}$"; then
  echo "⚠ Тег $TAG уже существует на remote"
  read -p "Перезаписать релиз? (y/N): " ans
  if [ "$ans" = "y" ]; then
    gh release delete "$TAG" --yes 2>/dev/null || true
  else
    echo "Отменено"
    exit 1
  fi
fi

# CHANGELOG
CHANGELOG=$(cat "$RELEASE_DIR/CHANGELOG.txt" 2>/dev/null || echo "Автообновление клиента, исправления UI")

# Создаём релиз
echo "→ Создаю release $TAG"
gh release create "$TAG" \
  --title "$NAME" \
  --notes "## Changelog

$CHANGELOG

## Ассеты
- x86_64: для эмуляторов
- arm64: для реальных устройств
- universal (если собран): fallback для всех ABI

Клиент при входе проверит релизы и предложит обновление." \
  "$RELEASE_DIR"/*.apk \
  "$RELEASE_DIR"/*.sha256

echo ""
echo "✓ Релиз опубликован:"
echo "  https://github.com/$REPO/releases/tag/$TAG"
