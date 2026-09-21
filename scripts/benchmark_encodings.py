#!/usr/bin/env python3
"""Benchmark RAW / Hextile / automatique sur la tablette réelle (SS-063). Bibliothèque standard seulement.

    python3 scripts/benchmark_encodings.py --out /chemin/bench --host 192.168.1.199 [--passes 2] [--cycles 2] [--phase-s 45]

Enchaîne, pour chaque passe, une session par encodage (raw, hextile, auto ; l'ordre est le même à chaque passe, les passes
sont entrelacées pour que les variations du Wi-Fi n'avantagent pas toujours le même encodage), chacune contre la même charge
serveur, **sans entrées tactiles ni échantillons meminfo** (ils coûteraient du processeur à la tablette pendant la mesure).
Prérequis : ceux de scripts/reference_session.py et `scripts/reference-server.sh up`. Analyse : scripts/analyze_benchmark.py."""
import argparse, json, os, subprocess, sys

PHASES = "clock,scatter,scroll,windows,flash,noise,idle"
MODES = ["raw", "hextile", "auto"]


def session_complete(out):
    """Vrai si la session a été menée à son terme (pas interrompue) : le benchmark est alors reprenable."""
    try:
        meta = json.load(open(os.path.join(out, "meta.json")))
    except (OSError, ValueError):
        return False
    return "host_end_epoch" in meta and "interrupted_after_s" not in meta and "battery_stop_pct" not in meta


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True)
    ap.add_argument("--host", required=True)
    ap.add_argument("--port", type=int, default=5901)
    ap.add_argument("--passes", type=int, default=2)
    ap.add_argument("--cycles", type=int, default=2, help="cycles complets des phases par session")
    ap.add_argument("--phase-s", type=int, default=45)
    ap.add_argument("--adb-connect", help="adresse:port de la tablette pour adb par Wi-Fi (chargeur secteur, sans câble USB)")
    a = ap.parse_args()
    here = os.path.dirname(os.path.abspath(__file__))
    minutes = a.cycles * len(PHASES.split(",")) * a.phase_s / 60 + 0.5  # + marge pour la dérive de la charge
    for n in range(1, a.passes + 1):
        for mode in MODES:
            out = os.path.join(a.out, f"pass{n}-{mode}")
            if session_complete(out):
                print(f"=== passe {n}, encodage {mode} : déjà faite, ignorée", flush=True)
                continue
            if os.path.exists(out):  # session incomplète d'un lancement précédent : on la met de côté, on ne la supprime pas
                k = 1
                while os.path.exists(f"{out}.incomplete{k}"):
                    k += 1
                os.rename(out, f"{out}.incomplete{k}")
            print(f"=== passe {n}, encodage {mode} -> {out} ({minutes:.1f} min)", flush=True)
            r = subprocess.run([sys.executable, os.path.join(here, "reference_session.py"), "--minutes", f"{minutes:.2f}", "--out", out,
                                "--host", a.host, "--port", str(a.port), "--phase-s", str(a.phase_s), "--phases", PHASES,
                                "--encoding", mode, "--input-every-s", "0", "--sample-every-s", "0", "--dim"] + (["--adb-connect", a.adb_connect] if a.adb_connect else []))
            if r.returncode != 0:
                raise SystemExit(f"session {n}/{mode} en échec (code {r.returncode}) : benchmark interrompu")
    print("terminé", flush=True)


if __name__ == "__main__":
    main()
