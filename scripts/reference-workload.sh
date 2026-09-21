#!/bin/sh
# Charge de travail des sessions de référence et du benchmark d'encodages (SS-061, SS-063), exécutée DANS le conteneur du
# serveur VNC (DISPLAY=:1). Les phases de PHASES (séparées par des espaces) durent PHASE_S secondes (60 par défaut) et se
# répètent en boucle jusqu'à ce qu'on tue ce script. Phases connues :
#   clock    une petite fenêtre qui change une fois par seconde
#   scatter  deux petites zones aux coins opposés qui changent 20 fois par seconde
#   scroll   un grand terminal qui défile en continu (charge maximale de décodage)
#   windows  une grande fenêtre ouverte puis fermée toutes les 2 s (grands aplats, fond redessiné)
#   flash    tout l'écran change de couleur unie toutes les 3 s (un écran complet, très compressible)
#   noise    tout l'écran alterne entre deux images de bruit toutes les 3 s (un écran complet incompressible : le pire cas)
#   idle     rien ne change (seul le battement de la tablette circule)
export DISPLAY=:1
PHASE_S=${PHASE_S:-60}
PHASES=${PHASES:-"clock scatter scroll windows idle"}

clear_windows() {
    for d in /proc/[0-9]*; do
        case "$(cat "$d/comm" 2>/dev/null)" in xterm) kill "${d#/proc/}" 2>/dev/null;; esac
    done
    sleep 0.3
}

phase() { echo "$(date +%s) $1" >> /tmp/workload-phases.log; }

# Deux images de bruit 1280x800 (PPM, ~3 Mo), créées une fois : chaque affichage change tous les pixels.
make_noise() {
    for n in 1 2; do
        [ -s /tmp/noise$n.ppm ] && continue
        printf 'P6\n1280 800\n255\n' > /tmp/noise$n.ppm
        head -c 3072000 /dev/urandom >> /tmp/noise$n.ppm
    done
}

run_phase() {
    case "$1" in
    clock)
        xterm -bg darkgreen -fg white -geometry 30x2+100+100 -fa Monospace -fs 14 -e sh -c 'while true; do date; sleep 1; done' &
        sleep "$PHASE_S"; clear_windows;;
    scatter)
        xterm -bg darkgreen -fg white -geometry 12x1+0+0 -fa Monospace -fs 14 -e sh -c 'i=0; while true; do i=$((i+1)); printf "\r%s" $i; sleep 0.05; done' &
        xterm -bg darkred -fg white -geometry 12x1-0+300 -fa Monospace -fs 14 -e sh -c 'i=0; while true; do i=$((i+1)); printf "\r%s" $i; sleep 0.05; done' &
        sleep "$PHASE_S"; clear_windows;;
    scroll)
        xterm -bg black -fg white -geometry 150x45+100+100 -fa Monospace -fs 14 -e sh -c 'i=0; while true; do i=$((i+1)); echo "ligne $i : le renard brun saute par-dessus le chien paresseux 0123456789"; done' &
        sleep "$PHASE_S"; clear_windows;;
    windows)
        end=$(( $(date +%s) + PHASE_S ))
        while [ "$(date +%s)" -lt "$end" ]; do
            xterm -bg navy -fg white -geometry 100x30+150+80 -fa Monospace -fs 14 -e sh -c 'sleep 60' &
            sleep 2; clear_windows
        done;;
    flash)
        end=$(( $(date +%s) + PHASE_S ))
        while [ "$(date +%s)" -lt "$end" ]; do
            xsetroot -solid "#804020"; sleep 3
            xsetroot -solid "#204080"; sleep 3
        done
        xsetroot -solid black;;
    noise)
        make_noise
        end=$(( $(date +%s) + PHASE_S ))
        while [ "$(date +%s)" -lt "$end" ]; do
            feh --no-fehbg --bg-center /tmp/noise1.ppm; sleep 3
            feh --no-fehbg --bg-center /tmp/noise2.ppm; sleep 3
        done
        xsetroot -solid black;;
    idle)
        sleep "$PHASE_S";;
    esac
}

xsetroot -solid black
while true; do
    for p in $PHASES; do
        phase "$p"
        run_phase "$p"
    done
done
