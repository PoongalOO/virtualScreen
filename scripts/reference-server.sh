#!/bin/bash
# Serveur VNC de la session de référence (SS-061) : TigerVNC 1280x800 SANS mot de passe, dans un conteneur jetable.
#   scripts/reference-server.sh up [adresse-lan]   # l'adresse sur laquelle publier le port (défaut : IP LAN du PC)
#   scripts/reference-server.sh down
# Sans mot de passe : n'exposer QUE sur le réseau local de confiance (jamais sur Internet), et le supprimer après le test.
set -eu
NAME=${NAME:-ss-vnc-test}
PORT=${PORT:-5901}
BASE_IMAGE=${BASE_IMAGE:-nginx:latest}   # n'importe quelle image Debian/Ubuntu : on n'y utilise que apt
HERE=$(cd "$(dirname "$0")" && pwd)

case "${1:-}" in
  up)
    BIND=${2:-$(ip -4 route get 1.1.1.1 | sed -n 's/.* src \([0-9.]*\).*/\1/p')}
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    docker run -d --name "$NAME" --memory 1g --cpus 2 -p "$BIND:$PORT:5900" --entrypoint sleep "$BASE_IMAGE" infinity >/dev/null
    docker exec "$NAME" sh -c 'apt-get update -qq >/dev/null 2>&1; DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends tigervnc-standalone-server x11-utils xterm >/dev/null 2>&1; which Xtigervnc xterm >/dev/null'
    docker cp "$HERE/reference-workload.sh" "$NAME:/tmp/workload.sh"
    docker exec -d "$NAME" sh -c 'Xtigervnc :1 -geometry 1280x800 -depth 24 -rfbport 5900 -SecurityTypes None -AlwaysShared -interface 0.0.0.0 > /tmp/xvnc.log 2>&1'
    sleep 3
    echo "serveur prêt : $BIND:$PORT (1280x800, sans mot de passe, réseau local seulement)"
    ;;
  down)
    docker rm -f "$NAME" >/dev/null 2>&1 && echo "conteneur $NAME supprimé" || echo "rien à supprimer"
    ;;
  *) echo "usage: $0 up [adresse-lan] | down" >&2; exit 2;;
esac
