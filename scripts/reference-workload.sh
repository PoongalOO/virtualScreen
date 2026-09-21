#!/bin/sh
# Charge de travail de la session de référence (SS-061), exécutée DANS le conteneur du serveur VNC (DISPLAY=:1).
# Cinq phases de PHASE_S secondes (60 par défaut), en boucle jusqu'à ce qu'on tue ce script :
#   clock    une petite fenêtre qui change une fois par seconde
#   scatter  deux petites zones aux coins opposés qui changent 20 fois par seconde
#   scroll   un grand terminal qui défile en continu (charge maximale de décodage)
#   windows  une grande fenêtre ouverte puis fermée toutes les 2 s (grands rectangles, fond redessiné)
#   idle     rien ne change (seul le battement de la tablette circule)
export DISPLAY=:1
PHASE_S=${PHASE_S:-60}

clear_windows() {
    for d in /proc/[0-9]*; do
        case "$(cat "$d/comm" 2>/dev/null)" in xterm) kill "${d#/proc/}" 2>/dev/null;; esac
    done
    sleep 0.3
}

phase() { echo "$(date +%s) $1" >> /tmp/workload-phases.log; }

while true; do
    phase clock
    xterm -bg darkgreen -fg white -geometry 30x2+100+100 -fa Monospace -fs 14 -e sh -c 'while true; do date; sleep 1; done' &
    sleep "$PHASE_S"; clear_windows

    phase scatter
    xterm -bg darkgreen -fg white -geometry 12x1+0+0 -fa Monospace -fs 14 -e sh -c 'i=0; while true; do i=$((i+1)); printf "\r%s" $i; sleep 0.05; done' &
    xterm -bg darkred -fg white -geometry 12x1-0+300 -fa Monospace -fs 14 -e sh -c 'i=0; while true; do i=$((i+1)); printf "\r%s" $i; sleep 0.05; done' &
    sleep "$PHASE_S"; clear_windows

    phase scroll
    xterm -bg black -fg white -geometry 150x45+100+100 -fa Monospace -fs 14 -e sh -c 'i=0; while true; do i=$((i+1)); echo "ligne $i : le renard brun saute par-dessus le chien paresseux 0123456789"; done' &
    sleep "$PHASE_S"; clear_windows

    phase windows
    end=$(( $(date +%s) + PHASE_S ))
    while [ "$(date +%s)" -lt "$end" ]; do
        xterm -bg navy -fg white -geometry 100x30+150+80 -fa Monospace -fs 14 -e sh -c 'sleep 60' &
        sleep 2; clear_windows
    done

    phase idle
    sleep "$PHASE_S"
done
