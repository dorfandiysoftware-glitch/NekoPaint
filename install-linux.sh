#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# NekoPaint installer for Linux (x86_64). Installs into your home directory, no root needed.
#
#   curl -fsSL https://raw.githubusercontent.com/dorfandiysoftware-glitch/NekoPaint/main/install-linux.sh | sh
#
# Options (environment variables or flags):
#   NEKOPAINT_VERSION=v0.3.0-linux1   install a specific release instead of the newest Linux one
#   --uninstall                       remove NekoPaint again
#   --deps                            also install runtime packages with pacman/apt/dnf (uses sudo)
set -eu

REPO="dorfandiysoftware-glitch/NekoPaint"
PREFIX="${HOME}/.local"
APPDIR="${PREFIX}/opt/nekopaint"
BINLINK="${PREFIX}/bin/nekopaint"
DESKTOP="${PREFIX}/share/applications/nekopaint.desktop"

say() { printf '%s\n' "$*"; }
die() { printf 'Помилка: %s\n' "$*" >&2; exit 1; }

DEPS=0
for arg in "$@"; do
    case "$arg" in
        --uninstall)
            rm -rf "$APPDIR" "$BINLINK" "$DESKTOP"
            say "NekoPaint видалено."
            exit 0 ;;
        --deps) DEPS=1 ;;
        *) die "невідомий параметр: $arg" ;;
    esac
done

[ "$(uname -s)" = "Linux" ] || die "скрипт тільки для Linux."
case "$(uname -m)" in
    x86_64|amd64) ;;
    *) die "поки що є збірка лише для x86_64, у вас $(uname -m)." ;;
esac

if command -v curl >/dev/null 2>&1; then
    fetch() { curl -fsSL "$1"; }
    download() { curl -fL --progress-bar -o "$2" "$1"; }
elif command -v wget >/dev/null 2>&1; then
    fetch() { wget -qO- "$1"; }
    download() { wget -O "$2" "$1"; }
else
    die "потрібен curl або wget."
fi
command -v tar >/dev/null 2>&1 || die "потрібен tar."

if [ "$DEPS" = "1" ]; then
    if command -v pacman >/dev/null 2>&1; then
        sudo pacman -S --needed --noconfirm xorg-xwayland fontconfig libx11 libice libsm
    elif command -v apt-get >/dev/null 2>&1; then
        sudo apt-get install -y xwayland libfontconfig1 libx11-6 libice6 libsm6
    elif command -v dnf >/dev/null 2>&1; then
        sudo dnf install -y xorg-x11-server-Xwayland fontconfig libX11 libICE libSM
    else
        say "Не знаю вашого пакетного менеджера: встановіть вручну XWayland, fontconfig, libX11, libICE, libSM."
    fi
fi

VERSION="${NEKOPAINT_VERSION:-}"
if [ -n "$VERSION" ]; then
    URL="https://github.com/${REPO}/releases/download/${VERSION}/NekoPaint-Linux-${VERSION}-x64.tar.gz"
else
    say "Шукаю останню Linux-збірку..."
    # /releases/latest ignores pre-releases, so read the list (newest first) and take the first Linux asset.
    URL="$(fetch "https://api.github.com/repos/${REPO}/releases?per_page=30" \
        | grep -o '"browser_download_url": *"[^"]*NekoPaint-Linux-[^"]*x64\.tar\.gz"' \
        | head -n 1 | sed 's/.*"\(https[^"]*\)"$/\1/')" || true
    [ -n "$URL" ] || die "не знайшов Linux-збірку в релізах https://github.com/${REPO}/releases"
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT INT TERM
say "Завантажую: $URL"
download "$URL" "$TMP/nekopaint.tar.gz"

say "Розпаковую у $APPDIR"
rm -rf "$APPDIR.new"
mkdir -p "$APPDIR.new"
tar -xzf "$TMP/nekopaint.tar.gz" -C "$APPDIR.new"
[ -x "$APPDIR.new/NekoPaint" ] || chmod +x "$APPDIR.new/NekoPaint"
[ -f "$APPDIR.new/libnekocore.so" ] || say "Увага: libnekocore.so немає в архіві, працюватиме повільніший C# шлях."
rm -rf "$APPDIR"
mv "$APPDIR.new" "$APPDIR"

mkdir -p "${PREFIX}/bin" "${PREFIX}/share/applications"
ln -sf "$APPDIR/NekoPaint" "$BINLINK"
cat > "$DESKTOP" <<DESKTOP_EOF
[Desktop Entry]
Type=Application
Name=NekoPaint
Comment=Drawing app
Exec=$APPDIR/NekoPaint %f
Icon=applications-graphics
Terminal=false
Categories=Graphics;2DGraphics;RasterGraphics;
MimeType=image/openraster;
DESKTOP_EOF

say ""
say "Готово. Запуск: nekopaint"
case ":${PATH}:" in
    *":${PREFIX}/bin:"*) ;;
    *) say "Додайте ${PREFIX}/bin до PATH (наприклад: export PATH=\"\$HOME/.local/bin:\$PATH\") або запускайте $APPDIR/NekoPaint" ;;
esac
if ! command -v Xwayland >/dev/null 2>&1 && [ -n "${WAYLAND_DISPLAY:-}" ]; then
    say "Схоже, XWayland не встановлено, а NekoPaint працює через нього. На Arch: sudo pacman -S xorg-xwayland (або запустіть цей скрипт із --deps)."
fi
say "Видалити: sh install-linux.sh --uninstall"
