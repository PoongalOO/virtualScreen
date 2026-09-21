#!/usr/bin/env python3
"""Session de référence (SS-061) : fait tourner l'application sur la tablette pendant N minutes contre une charge variée et
enregistre de quoi mesurer les allocations et détecter une fuite. Bibliothèque standard seulement.

    python3 scripts/reference_session.py --minutes 30 --out /chemin/session30 --host 192.168.1.199

Prérequis : tablette en USB (adb), APK debug installé, `scripts/reference-server.sh up` lancé. Enregistre dans --out :
  logcat.txt   lignes SecondScreenPerf (une par seconde) et ramasse-miettes Dalvik
  samples.csv  toutes les 30 s : PSS, tas Java/natif, threads, descripteurs, objets Views/Activities... (dumpsys meminfo)
  events.log   entrées envoyées, changements d'écran, anomalies
  meta.json    paramètres et horloges
L'analyse se fait avec scripts/analyze_session.py. Le mot de passe VNC n'est jamais utilisé (serveur sans mot de passe)."""
import argparse, csv, json, os, random, re, subprocess, sys, tempfile, time
from datetime import datetime

PKG = "fr.webinfoconcept.secondscreen"
ADB = os.environ.get("ADB", os.path.expanduser("~/Android/Sdk/platform-tools/adb"))
CONTAINER = os.environ.get("NAME", "ss-vnc-test")


def adb(*args, check=True, timeout=60):
    r = subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)} : {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout.replace("\r", "")


def sh(cmd, **kw):
    return adb("shell", cmd, **kw)


def ensure_screen_on():
    """Allume l'écran s'il est éteint. KEYCODE_WAKEUP n'existe qu'à partir de l'API 20 : sur 4.2 on bascule avec la touche Marche."""
    if "mScreenOn=false" in sh("dumpsys power | grep mScreenOn=", check=False):
        sh("input keyevent KEYCODE_POWER")
        time.sleep(1.5)
        sh("input keyevent 82")  # MENU : ferme le verrouillage simple
        time.sleep(1)


def focus():
    m = re.search(r"mCurrentFocus=Window\{\S+ \S+ (\S+?)\}", sh("dumpsys window | grep mCurrentFocus"))
    return m.group(1).split("/")[-1].split(".")[-1] if m else "?"


def pid():
    for line in sh("ps").splitlines():
        cols = line.split()
        if cols and cols[-1] == PKG:
            return int(cols[1])
    return None


def ui_nodes():
    sh("uiautomator dump /sdcard/ui.xml")
    xml = sh("cat /sdcard/ui.xml")
    out = []
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?class="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        t, c, l, tp, r, b = m.groups()
        out.append((t, c.split(".")[-1], (int(l) + int(r)) // 2, (int(tp) + int(b)) // 2))
    return out


def tap(x, y):
    sh(f"input tap {x} {y}")


def hide_keyboard():
    """Ferme le clavier logiciel s'il est affiché (Retour ferme d'abord le clavier ; sans lui il quitterait l'écran)."""
    if "mInputShown=true" in sh("dumpsys input_method | grep mInputShown", check=False):
        sh("input keyevent KEYCODE_BACK")
        time.sleep(1)


def find(text_start, cls=None):
    for t, c, x, y in ui_nodes():
        if t.startswith(text_start) and (cls is None or c == cls):
            return x, y
    return None


def connect(host, port):
    """Ouvre la session depuis l'écran d'accueil : « Reconnecter » si un profil existe, sinon crée la connexion."""
    sh(f"am force-stop {PKG}")
    time.sleep(1)
    sh(f"am start -n {PKG}/.MainActivity")
    time.sleep(2)
    p = find("Reconnecter")
    if p is None:
        tap(*find("Ajouter", "Button"))
        time.sleep(2)
        for index, value in ((1, host), (2, str(port))):  # champs : nom, adresse, port, mot de passe
            hide_keyboard()  # le clavier décale la mise en page : on relit les coordonnées à chaque champ
            current, x, y = [(t, x, y) for t, c, x, y in ui_nodes() if c == "EditText"][index][0:3]
            tap(x, y)
            if current:  # champ prérempli : on l'efface (chaque appui coûte ~1 s sur la tablette, donc seulement si nécessaire)
                sh("input keyevent KEYCODE_MOVE_END")
                for _ in range(len(current) + 2):
                    sh("input keyevent KEYCODE_DEL")
            sh(f"input text {value}")
        hide_keyboard()
        button = find("Connexion", "Button")
        if button is None:
            raise SystemExit("bouton Connexion introuvable dans l'écran de connexion")
        tap(*button)
    else:
        tap(*p)
        time.sleep(2)
        tap(*find("Connexion", "Button"))
    for _ in range(20):
        time.sleep(1)
        if focus() == "RemoteActivity":
            return
    raise SystemExit(f"la session ne s'est pas ouverte (écran : {focus()})")


def set_display_settings(perf=True, fit=False):
    """Écrit les réglages d'affichage. **Les valeurs sont des chaînes** (`PreferencesStore` ne stocke que des chaînes) : un
    booléen XML serait ignoré sans erreur et les mesures resteraient désactivées."""
    xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
           f'<string name="show_performance">{"true" if perf else "false"}</string>\n'
           f'<string name="fit_to_screen">{"true" if fit else "false"}</string>\n</map>\n')
    with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False) as f:
        f.write(xml)
    adb("push", f.name, "/data/local/tmp/ds.xml")
    os.unlink(f.name)
    sh(f"run-as {PKG} sh -c 'cat /data/local/tmp/ds.xml > shared_prefs/display_settings.xml'")
    sh("rm -f /data/local/tmp/ds.xml")
    written = sh(f"run-as {PKG} cat shared_prefs/display_settings.xml")
    if f'name="show_performance">{"true" if perf else "false"}<' not in written:
        raise SystemExit("les réglages d'affichage n'ont pas été écrits (application sans dossier de préférences ?)")


MEM_KEYS = ["TOTAL", "Dalvik Heap", "Native Heap", "Views:", "ViewRootImpl:", "AppContexts:", "Activities:", "Assets:",
            "Local Binders:", "Proxy Binders:", "Death Recipients:", "OpenSSL Sockets:"]


def memory_sample(p):
    text = sh(f"dumpsys meminfo {PKG}")
    row = {}
    for line in text.splitlines():
        s = line.strip()
        for key in ("TOTAL", "Dalvik Heap", "Native Heap"):
            if s.startswith(key + " ") and key not in row:
                nums = re.findall(r"-?\d+", s)
                row[key.replace(" ", "_") + "_pss_kb"] = int(nums[0]) if nums else ""
        for key in ("Views", "ViewRootImpl", "AppContexts", "Activities", "Assets", "Local Binders", "Proxy Binders", "Death Recipients", "OpenSSL Sockets"):
            m = re.search(re.escape(key) + r":\s+(\d+)", s)
            if m:
                row.setdefault("obj_" + key.replace(" ", "_"), int(m.group(1)))
    status = sh(f"cat /proc/{p}/status", check=False)
    for k, name in (("VmRSS", "rss_kb"), ("Threads", "threads")):
        m = re.search(k + r":\s+(\d+)", status)
        row[name] = int(m.group(1)) if m else ""
    fds = sh(f"run-as {PKG} ls /proc/{p}/fd", check=False).split()
    row["fds"] = len(fds) if fds else ""
    return row


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--minutes", type=float, required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--host", required=True, help="adresse LAN du serveur VNC (celle de reference-server.sh)")
    ap.add_argument("--port", type=int, default=5901)
    ap.add_argument("--phase-s", type=int, default=60)
    ap.add_argument("--input-every-s", type=int, default=15, help="période des entrées tactiles (0 = aucune)")
    ap.add_argument("--no-perf", action="store_true", help="mesures désactivées (configuration de production) : seuls les ramasse-miettes et dumpsys sont enregistrés")
    ap.add_argument("--fit", action="store_true", help="rendu mis à l'échelle (option « Échelle : ajustée »)")
    ap.add_argument("--seed", type=int, default=61)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    rnd = random.Random(a.seed)
    events = open(os.path.join(a.out, "events.log"), "a", buffering=1)

    def log(msg):
        events.write(f"{datetime.now():%H:%M:%S} {msg}\n")

    if PKG not in adb("shell", "pm", "list", "packages", PKG):
        raise SystemExit("application non installée")
    if subprocess.run(["docker", "exec", CONTAINER, "true"], capture_output=True).returncode != 0:
        raise SystemExit(f"conteneur {CONTAINER} absent : lancer scripts/reference-server.sh up")

    old_stay = sh("settings get global stay_on_while_plugged_in").strip()
    sh("settings put global stay_on_while_plugged_in 3")  # écran allumé pendant toute la session (restauré à la fin)
    ensure_screen_on()
    set_display_settings(perf=not a.no_perf, fit=a.fit)
    logcat = None
    try:
        connect(a.host, a.port)
        p = pid()
        log(f"session ouverte, pid {p}")
        adb("logcat", "-c")
        logcat = subprocess.Popen([ADB, "logcat", "-v", "time", f"SecondScreenPerf:I", "dalvikvm:D", "*:S"],
                                  stdout=open(os.path.join(a.out, "logcat.txt"), "w"), stderr=subprocess.DEVNULL)
        time.sleep(4)
        if not a.no_perf and "SecondScreenPerf" not in adb("logcat", "-d", "-s", "SecondScreenPerf:I"):
            raise SystemExit("aucune ligne de mesure dans le journal : les mesures ne sont pas activées")
        subprocess.run(["docker", "exec", CONTAINER, "sh", "-c", "rm -f /tmp/workload-phases.log"])
        subprocess.run(["docker", "exec", "-d", "-e", f"PHASE_S={a.phase_s}", CONTAINER, "sh", "/tmp/workload.sh"], check=True)
        start = time.time()
        meta = {"perf": not a.no_perf, "fit": a.fit, "minutes": a.minutes, "phase_s": a.phase_s, "phases": ["clock", "scatter", "scroll", "windows", "idle"],
                "device_start_local": sh("date '+%Y-%m-%d %H:%M:%S'").strip(), "host_start_epoch": start,
                "android": sh("getprop ro.build.version.release").strip(), "model": sh("getprop ro.product.model").strip(),
                "app_version": re.search(r"versionName=(\S+)", sh(f"dumpsys package {PKG}")).group(1)}
        json.dump(meta, open(os.path.join(a.out, "meta.json"), "w"), indent=1)

        cols = None
        csvf = open(os.path.join(a.out, "samples.csv"), "w", newline="")
        writer = None
        next_sample = start
        next_input = start + a.input_every_s
        next_check = start + 60
        n_input = 0
        end = start + a.minutes * 60
        while time.time() < end:
            now = time.time()
            if now >= next_sample:
                cur = pid()
                if cur is None:
                    log("ANOMALIE : le processus de l'application n'existe plus")
                    raise SystemExit("l'application a disparu : voir events.log")
                if cur != p:
                    log(f"ANOMALIE : pid changé {p} -> {cur} (l'application a redémarré)")
                    p = cur
                row = {"t_s": int(now - start), "pid": p, **memory_sample(p)}
                if writer is None:
                    writer = csv.DictWriter(csvf, fieldnames=list(row))
                    writer.writeheader()
                writer.writerow(row)
                csvf.flush()
                next_sample += 30
            if a.input_every_s and now >= next_input:
                x, y = rnd.randint(150, 1100), rnd.randint(150, 650)
                kind = n_input % 4
                if kind == 0:
                    sh(f"input tap {x} {y}"); log(f"tap {x},{y}")
                elif kind == 1:
                    sh(f"input swipe {x} {y} {x + rnd.randint(-100, 100)} {y + rnd.randint(-80, 80)} 300"); log(f"glissement depuis {x},{y}")
                elif kind == 2:
                    sh("input text abc"); log("texte abc")
                else:
                    sh("input keyevent KEYCODE_DEL"); log("touche effacer")
                n_input += 1
                next_input += a.input_every_s
            if now >= next_check:
                f = focus()
                if f != "RemoteActivity":
                    log(f"ANOMALIE : l'écran au premier plan est {f}")
                next_check += 60
            time.sleep(1)
        log(f"fin normale après {int(time.time() - start)} s, {n_input} entrées envoyées")
        meta["host_end_epoch"] = time.time()
        meta["device_end_local"] = sh("date '+%Y-%m-%d %H:%M:%S'").strip()
        json.dump(meta, open(os.path.join(a.out, "meta.json"), "w"), indent=1)
    finally:
        # Arrête la charge (le script et ses fenêtres xterm), pas le serveur.
        subprocess.run(["docker", "exec", CONTAINER, "sh", "-c",
                        'for d in /proc/[0-9]*; do c=$(tr "\\0" " " < $d/cmdline 2>/dev/null); n=$(cat $d/comm 2>/dev/null); '
                        'case "$n|$c" in xterm\\|*|*workload.sh*) [ "${d#/proc/}" != "$$" ] && kill ${d#/proc/} 2>/dev/null;; esac; done; true'],
                       capture_output=True)
        if logcat:
            time.sleep(2)
            logcat.terminate()
        subprocess.run(["docker", "cp", f"{CONTAINER}:/tmp/workload-phases.log", os.path.join(a.out, "workload-phases.log")], capture_output=True)
        sh(f"settings put global stay_on_while_plugged_in {old_stay or 0}")
        log("environnement restauré")


if __name__ == "__main__":
    main()
