#!/usr/bin/env python3
"""Analyse d'une session de référence enregistrée par scripts/reference_session.py (SS-061). Bibliothèque standard seulement.

    python3 scripts/analyze_session.py /chemin/session30 [--warmup-s 30]

Sortie en Markdown : allocations par phase de la charge, ramasse-miettes, et tendances (tas Java après GC, PSS, threads,
descripteurs, objets Views/Activities) pour repérer une fuite. Les « alertes » sont des indices, pas un verdict."""
import argparse, csv, json, os, re, statistics
from datetime import datetime

PERF = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d)\.\d+ I/SecondScreenPerf\(\s*(\d+)\): (.*)$")
GC = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d)\.\d+ [DI]/dalvikvm\(\s*(\d+)\): (GC_\w+) freed\s+<?(\d+)K, (\d+)% free (\d+)K/(\d+)K, paused ([^,]+), total (\d+)ms")
KV = re.compile(r"([\w/]+)=(-?[\d.]+)")


def slope_per_hour(points):
    """Pente d'une régression linéaire (x en secondes) ramenée à l'heure ; None s'il y a moins de 3 points."""
    if len(points) < 3:
        return None
    xs, ys = [p[0] for p in points], [p[1] for p in points]
    mx, my = statistics.fmean(xs), statistics.fmean(ys)
    den = sum((x - mx) ** 2 for x in xs)
    return None if den == 0 else 3600 * sum((x - mx) * (y - my) for x, y in points) / den


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dir")
    ap.add_argument("--warmup-s", type=int, default=30, help="secondes ignorées au début (JIT, premières allocations)")
    a = ap.parse_args()
    meta = json.load(open(os.path.join(a.dir, "meta.json")))
    year = int(meta["device_start_local"][:4])
    t0 = datetime.strptime(meta["device_start_local"], "%Y-%m-%d %H:%M:%S")
    phases = meta["phases"]; phase_s = meta["phase_s"]

    def rel(stamp):
        return (datetime.strptime(f"{year}-{stamp}", "%Y-%m-%d %H:%M:%S") - t0).total_seconds()

    perf, gcs, pids = [], [], set()
    for line in open(os.path.join(a.dir, "logcat.txt"), errors="replace"):
        m = PERF.match(line.strip())
        if m:
            d = {k: float(v) for k, v in KV.findall(m.group(3))}
            d["t"] = rel(m.group(1)); d["pid"] = int(m.group(2)); pids.add(d["pid"]); perf.append(d)
            continue
        m = GC.match(line.strip())
        if m:
            gcs.append({"t": rel(m.group(1)), "pid": int(m.group(2)), "kind": m.group(3), "freed_kb": int(m.group(4)),
                        "free_pct": int(m.group(5)), "used_kb": int(m.group(6)), "total_kb": int(m.group(7)), "total_ms": int(m.group(9))})
    # Les lignes GC de dalvikvm viennent de tous les processus : on ne garde que ceux qui ont écrit des mesures.
    if pids:
        gcs = [g for g in gcs if g["pid"] in pids]
    else:  # mesures désactivées : aucune ligne de mesure, donc pas de pid : on garde les pids de l'application connus par samples.csv
        sp0 = os.path.join(a.dir, "samples.csv")
        known = {int(r["pid"]) for r in csv.DictReader(open(sp0))} if os.path.exists(sp0) else set()
        pids = known
        gcs = [g for g in gcs if g["pid"] in known]
    perf = [p for p in perf if p["t"] >= a.warmup_s and "sess_alloc/s" in p]
    if not perf and meta.get("perf", True):
        raise SystemExit("aucune ligne de mesure exploitable (mesures activées ? champs d'allocation présents ?)")

    out = []
    w = out.append
    has_perf = bool(perf)
    span = (perf[-1]["t"] - perf[0]["t"]) if has_perf else meta["minutes"] * 60 - a.warmup_s
    gaps = sum(1 for x, y in zip(perf, perf[1:]) if y["t"] - x["t"] > 3)
    mode = "mesures activées" if has_perf else "**mesures désactivées** (configuration de production)"
    w(f"## Session de référence : {meta['minutes']:g} min, {mode}, {meta.get('model', '?')} Android {meta.get('android', '?')}, application {meta.get('app_version', '?')}, rendu {'ajusté' if meta.get('fit') else '1:1'}\n")
    if has_perf:
        w(f"- lignes de mesure : **{len(perf)}** sur {span:.0f} s (après {a.warmup_s} s d'échauffement), trous > 3 s : **{gaps}** ; processus vus : {sorted(pids)}")
    else:
        pids = {g["pid"] for g in gcs} or pids
    ev = os.path.join(a.dir, "events.log")
    anomalies = [l.strip() for l in open(ev) if "ANOMALIE" in l] if os.path.exists(ev) else []
    w(f"- anomalies enregistrées : **{len(anomalies)}**" + ("".join(f"\n  - {l}" for l in anomalies[:10])))

    # ---- par phase
    if has_perf:
        def phase_of(t):
            return phases[int(t // phase_s) % len(phases)]

        def gc_per_min(name, seconds):  # ramasse-miettes lus dans le journal Dalvik (le compteur de Debug rend toujours 0 ici)
            return 60 * sum(1 for g in gcs if g["t"] >= a.warmup_s and phase_of(g["t"]) == name) / seconds
        groups = {}
        for p in perf:
            groups.setdefault(phase_of(p["t"]), []).append(p)
        w("\n### Allocations par phase de la charge\n")
        w("| Phase | s | maj/s | alloc. thread session /s | **par mise à jour** | o/s | alloc. processus /s | Ko/s | alloc. thread UI /s | GC/min | CPU % |")
        w("|---|---|---|---|---|---|---|---|---|---|---|")
        for name in phases:
            g = groups.get(name)
            if not g:
                continue
            upd = sum(x["maj/s"] for x in g)
            sess = sum(x["sess_alloc/s"] for x in g)
            per_upd = f"{sess / upd:.1f}" if upd > 0 else "—"
            mean = lambda k: statistics.fmean(x[k] for x in g)
            w(f"| {name} | {len(g)} | {mean('maj/s'):.1f} | {mean('sess_alloc/s'):.0f} | {per_upd} | {mean('sess_alloc_o/s'):.0f} | "
              f"{mean('alloc/s'):.0f} | {mean('alloc_ko/s'):.0f} | {mean('ui_alloc/s'):.0f} | {gc_per_min(name, len(g)):.1f} | {mean('cpu'):.0f} |")
        allg = perf
        w(f"| **toutes** | {len(allg)} | {statistics.fmean(x['maj/s'] for x in allg):.1f} | {statistics.fmean(x['sess_alloc/s'] for x in allg):.0f} | "
          f"{(sum(x['sess_alloc/s'] for x in allg) / max(1e-9, sum(x['maj/s'] for x in allg))):.1f} | {statistics.fmean(x['sess_alloc_o/s'] for x in allg):.0f} | "
          f"{statistics.fmean(x['alloc/s'] for x in allg):.0f} | {statistics.fmean(x['alloc_ko/s'] for x in allg):.0f} | {statistics.fmean(x['ui_alloc/s'] for x in allg):.0f} | "
          f"{60 * len([g for g in gcs if g['t'] >= a.warmup_s]) / len(allg):.1f} | {statistics.fmean(x['cpu'] for x in allg):.0f} |")
        w("\n« par mise à jour » = objets alloués par le thread de session divisés par les mises à jour reçues (phases sans mise à jour : —). "
          "Les mesures elles-mêmes (une ligne de journal et le texte du bandeau par seconde) sont comptées dans « thread UI », pas dans « thread session ».")

    # ---- ramasse-miettes et tas
    alerts = []
    w("\n### Ramasse-miettes et tas Java\n")
    late = [g for g in gcs if g["t"] >= a.warmup_s]
    if late:
        kinds = {}
        for g in late:
            kinds.setdefault(g["kind"], []).append(g)
        for k, v in sorted(kinds.items()):
            w(f"- {k} : **{len(v)}** ({len(v) * 60 / span:.1f}/min), libère {statistics.fmean(x['freed_kb'] for x in v):.0f} Ko en moyenne, "
              f"durée totale moyenne {statistics.fmean(x['total_ms'] for x in v):.0f} ms (max {max(x['total_ms'] for x in v)} ms)")
        freed = sum(g["freed_kb"] for g in late)
        w(f"- **allocation estimée par les ramasse-miettes** : {freed} Ko libérés en {span:.0f} s = **{freed / span:.1f} Ko/s** (borne basse : le tas non encore collecté n'est pas compté)")
        pts = [(g["t"], g["used_kb"]) for g in late]
        n = max(1, len(pts) // 5)
        first, last = [y for _, y in pts[:n]], [y for _, y in pts[-n:]]
        s = slope_per_hour(pts)
        w(f"- tas utilisé **après GC** : premier cinquième min/médiane {min(first)}/{statistics.median(first):.0f} Ko, dernier cinquième {min(last)}/{statistics.median(last):.0f} Ko, "
          f"pente {('%.0f Ko/h' % s) if s is not None else 'n/a'} ; plafond du tas : {max(g['total_kb'] for g in late)} Ko")
        if min(last) - min(first) > max(1024, 0.10 * min(first)):
            alerts.append(f"le minimum du tas après GC a augmenté de {min(last) - min(first)} Ko entre le début et la fin")
    else:
        w("- aucune ligne de ramasse-miettes Dalvik dans le journal (tas trop stable ou format de journal différent)")
    if has_perf:
        hp = [(p["t"], p["tas_ko"]) for p in perf]
        w(f"- tas utilisé (mesure de l'application, une par seconde, **avant** GC) : médiane {statistics.median(y for _, y in hp):.0f} Ko, max {max(y for _, y in hp):.0f} Ko")

    # ---- échantillons système
    sp = os.path.join(a.dir, "samples.csv")
    if os.path.exists(sp):
        rows = list(csv.DictReader(open(sp)))
        w("\n### Mémoire et ressources du processus (toutes les 30 s, `dumpsys meminfo`)\n")
        w("| Grandeur | début | fin | min | max | pente /h |")
        w("|---|---|---|---|---|---|")
        for col in [c for c in rows[0] if c not in ("t_s", "pid")]:
            pts = [(float(r["t_s"]), float(r[col])) for r in rows if r.get(col, "") != ""]
            if not pts:
                continue
            n = max(1, len(pts) // 5)
            ys = [y for _, y in pts]
            s = slope_per_hour(pts)
            w(f"| {col} | {statistics.median(ys[:n]):.0f} | {statistics.median(ys[-n:]):.0f} | {min(ys):.0f} | {max(ys):.0f} | {('%.0f' % s) if s is not None else 'n/a'} |")
            begin, end = statistics.median(ys[:n]), statistics.median(ys[-n:])
            if col.startswith("obj_") and col in ("obj_Activities", "obj_Views", "obj_ViewRootImpl", "obj_AppContexts") and end > begin * 1.5 + 2:
                alerts.append(f"{col} passe de {begin:.0f} à {end:.0f}")
            if col in ("threads", "fds") and end > begin + 5:
                alerts.append(f"{col} passe de {begin:.0f} à {end:.0f}")
            if col == "TOTAL_pss_kb" and end > begin * 1.10 and end - begin > 4096:
                alerts.append(f"PSS total passe de {begin:.0f} à {end:.0f} Ko")
    w("\n### Alertes (indices, à examiner)\n")
    w("\n".join(f"- ⚠ {x}" for x in alerts) if alerts else "- aucune : pas de croissance du minimum du tas après GC, du PSS, des threads, des descripteurs ni des objets d'interface au-delà des seuils.")
    w("\nSeuils : minimum du tas après GC > +10 % et +1 Mo ; PSS total > +10 % et +4 Mo ; threads/descripteurs > +5 ; Views/Activities/contextes > ×1,5 (+2). Comparaison des médianes du premier et du dernier cinquième des échantillons.")
    print("\n".join(out))


if __name__ == "__main__":
    main()
