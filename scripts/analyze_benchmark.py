#!/usr/bin/env python3
"""Compare les sessions de scripts/benchmark_encodings.py (SS-063). Bibliothèque standard seulement.

    python3 scripts/analyze_benchmark.py /chemin/bench [--warmup-s 15]

Une ligne par phase de la charge et par encodage : moyenne des sessions de cet encodage (et plage entre sessions). Le temps de
« décodage » d'un message inclut l'attente réseau de son corps ; pour un écran complet, « décodage + rendu » est le temps
qui sépare l'arrivée du début du message de l'image affichée : c'est la latence côté tablette."""
import argparse, glob, json, os, re, statistics
from datetime import datetime

PERF = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d)\.\d+ I/SecondScreenPerf\(\s*(\d+)\): (.*)$")
KV = re.compile(r"([\w/]+)=(-?[\d.]+)")
ENC = re.compile(r"enc=(\w+)")
FULL_UPDATE_MPX = 0.3  # une seconde où ont été décodés au moins ~0,3 Mpx compte comme « écran (presque) complet »


def load(d, warmup, trim):
    meta = json.load(open(os.path.join(d, "meta.json")))
    year = int(meta["device_start_local"][:4])
    t0 = datetime.strptime(meta["device_start_local"], "%Y-%m-%d %H:%M:%S")
    changes = []
    for line in open(os.path.join(d, "workload-phases.log")):
        parts = line.split()
        if len(parts) == 2:
            changes.append((float(parts[0]) - float(meta["host_start_epoch"]), parts[1]))

    def phase_at(t):
        """(phase, secondes écoulées depuis son début) : le début d'une phase contient la fin de la précédente (un écran
        de 4 Mo en RAW met plus d'une seconde à arriver), on le rogne."""
        cur, since = changes[0][1], t - changes[0][0]
        for when, name in changes:
            if when <= t:
                cur, since = name, t - when
            else:
                break
        return cur, since

    rows, encs = [], set()
    for line in open(os.path.join(d, "logcat.txt"), errors="replace"):
        m = PERF.match(line.strip())
        if not m:
            continue
        v = {k: float(x) for k, x in KV.findall(m.group(3))}
        e = ENC.search(m.group(3))
        if e:
            encs.add(e.group(1))
        t = (datetime.strptime(f"{year}-{m.group(1)}", "%Y-%m-%d %H:%M:%S") - t0).total_seconds()
        if t < warmup or "maj/s" not in v:
            continue
        v["t"] = t
        v["phase"], since = phase_at(t)
        if since < trim:
            continue
        rows.append(v)
    return meta, encs, rows


def summarize(rows):
    """Chiffres d'une phase : pondérés par le nombre de mises à jour ou de rendus, pas des moyennes de moyennes."""
    n = len(rows)
    upd = sum(r["maj/s"] for r in rows)
    ren = sum(r["rendus/s"] for r in rows)
    out = {
        "s": n,
        "maj": upd / n,
        "rendus": ren / n,
        "rx": sum(r["rx_ko/s"] for r in rows) / n,
        "rx_par_maj": (sum(r["rx_ko/s"] for r in rows) / upd) if upd > 0 else None,
        "decod": (sum(r["decod_ms"] * r["maj/s"] for r in rows) / upd) if upd > 0 else None,
        "rendu": (sum(r["rendu_ms"] * r["rendus/s"] for r in rows) / ren) if ren > 0 else None,
        "cpu": sum(r["cpu"] for r in rows) / n,
    }
    full = [r for r in rows if r["mpx/s"] >= FULL_UPDATE_MPX and r["maj/s"] > 0]
    out["ecran_complet"] = None
    if full:
        # Par seconde contenant un (ou quelques) écran complet : durée de réception+décodage puis de rendu de cette seconde.
        lat = [r["decod_ms"] + r["rendu_ms"] for r in full]
        out["ecran_complet"] = (statistics.median(lat), max(lat), len(full))
    return out


def fmt(x, digits=1):
    return "—" if x is None else f"{x:.{digits}f}"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dir")
    ap.add_argument("--warmup-s", type=int, default=15)
    ap.add_argument("--trim-s", type=int, default=6, help="secondes ignorées au début de chaque phase (fin de la phase précédente encore en transfert)")
    a = ap.parse_args()
    runs = {}
    for d in sorted(glob.glob(os.path.join(a.dir, "pass*-*"))):
        if not os.path.exists(os.path.join(d, "logcat.txt")):
            continue
        meta, encs, rows = load(d, a.warmup_s, a.trim_s)
        mode = meta["encoding"]
        interrupted = meta.get("interrupted_after_s")
        if encs and encs != {mode}:
            raise SystemExit(f"{d} : encodage demandé {mode} mais le journal dit {sorted(encs)}")
        by_phase = {}
        for r in rows:
            by_phase.setdefault(r["phase"], []).append(r)
        runs.setdefault(mode, []).append((os.path.basename(d), {p: summarize(v) for p, v in by_phase.items()}, len(rows), interrupted))
    if not runs:
        raise SystemExit("aucune session trouvée")
    order = [m for m in ("raw", "hextile", "auto") if m in runs]
    phases = []
    for m in order:
        for p in runs[m][0][1]:
            if p not in phases:
                phases.append(p)
    print(f"## Benchmark d'encodages : {sum(len(v) for v in runs.values())} sessions ({', '.join(f'{m} ×{len(runs[m])}' for m in order)})\n")
    for m in order:
        for name, _, n, interrupted in runs[m]:
            print(f"- {name} : {n} lignes de mesure" + (f", **interrompue après {interrupted} s**" if interrupted else ""))
    for p in phases:
        print(f"\n### Phase « {p} »\n")
        print("| Encodage | sessions | mises à jour/s | rendus/s | réseau Ko/s | Ko par mise à jour | décodage ms | rendu ms | CPU % (thread de session) | écran complet : décodage+rendu, médiane / max (ms) |")
        print("|---|---|---|---|---|---|---|---|---|---|")
        for m in order:
            ss = [r[1][p] for r in runs[m] if p in r[1]]
            if not ss:
                continue
            def mean(k):
                vals = [x[k] for x in ss if x[k] is not None]
                return statistics.fmean(vals) if vals else None
            def rng(k, digits=1):
                vals = [x[k] for x in ss if x[k] is not None]
                if len(vals) < 2:
                    return fmt(mean(k), digits)
                return f"{fmt(statistics.fmean(vals), digits)} ({fmt(min(vals), digits)}–{fmt(max(vals), digits)})"
            fulls = [x["ecran_complet"] for x in ss if x["ecran_complet"]]
            full = f"{statistics.fmean(f[0] for f in fulls):.0f} / {max(f[1] for f in fulls):.0f}" if fulls else "—"
            print(f"| {m} | {len(ss)} | {rng('maj')} | {rng('rendus')} | {rng('rx', 0)} | {rng('rx_par_maj', 1)} | {rng('decod')} | {rng('rendu')} | {rng('cpu', 0)} | {full} |")
    # ---- synthèse : rapports par rapport à RAW (moyennes des sessions)
    if "raw" in runs:
        print("\n### Synthèse : rapport à RAW (RAW = 1)\n")
        print("| Phase | encodage | octets par mise à jour | temps de décodage | mises à jour/s | latence d'un écran complet |")
        print("|---|---|---|---|---|---|")

        def avg(mode, p, key, sub=None):
            vals = []
            for _, per, _, _ in runs[mode]:
                if p in per and per[p][key] is not None:
                    vals.append(per[p][key][0] if sub == "full" else per[p][key])
            return statistics.fmean(vals) if vals else None

        def ratio(x, y):
            return "—" if x is None or y is None or y == 0 else f"×{x / y:.2f}"

        for p in phases:
            for m in order:
                if m == "raw":
                    continue
                print(f"| {p} | {m} | {ratio(avg(m, p, 'rx_par_maj'), avg('raw', p, 'rx_par_maj'))} | {ratio(avg(m, p, 'decod'), avg('raw', p, 'decod'))} | "
                      f"{ratio(avg(m, p, 'maj'), avg('raw', p, 'maj'))} | {ratio(avg(m, p, 'ecran_complet', 'full'), avg('raw', p, 'ecran_complet', 'full'))} |")
        print("\nUn rapport < 1 est un gain pour les octets, le décodage et la latence ; > 1 pour les mises à jour/s. À lire avec la phase : "
              "quand RAW est limité par le réseau, ses mises à jour/s sont bas et Hextile en fait plus dans le même temps.")
    print("\nLes valeurs entre parenthèses sont les extrêmes entre les sessions du même encodage (une par passe). "
          "« Ko par mise à jour » = octets reçus / mises à jour reçues (sans l'en-tête des messages ni le battement).")


if __name__ == "__main__":
    main()
