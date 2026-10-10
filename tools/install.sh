#!/bin/sh
# Installs mfrg for the current user (Linux, macOS). Needs Java 17 or newer and unzip.
#   curl -fsSL https://raw.githubusercontent.com/voksed/ModuForge/main/tools/install.sh | sh
set -eu

if ! command -v java >/dev/null 2>&1; then
    echo "Java 17 or newer is needed (for example: apt install openjdk-17-jre, brew install openjdk@17)." >&2
    exit 1
fi
target="${HOME}/.local/share/mfrg"
url="https://github.com/voksed/ModuForge/releases/latest/download/mfrg.zip"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "Downloading $url"
curl -fsSL "$url" -o "$tmp/mfrg.zip"
unzip -q "$tmp/mfrg.zip" -d "$tmp"
rm -rf "$target"
mkdir -p "$(dirname "$target")" "${HOME}/.local/bin"
mv "$tmp/mfrg" "$target"
chmod +x "$target/bin/mfrg"
ln -sf "$target/bin/mfrg" "${HOME}/.local/bin/mfrg"
echo "Installed. Make sure ${HOME}/.local/bin is on your PATH, then run: mfrg"
