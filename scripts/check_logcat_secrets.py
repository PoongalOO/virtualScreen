#!/usr/bin/env python3
"""Vérification sur la tablette (SS-071) : après une connexion authentifiée et un texte tapé, ni le mot de passe ni le texte ne
doivent apparaître dans AUCUN tampon de journal (main, system, events, radio), ni entiers ni par tranches de 4 caractères.

    python3 scripts/check_logcat_secrets.py --host 192.168.1.199 --port 5901 --password Qz7kVxLm

Prérequis : APK debug installé, tablette joignable par adb, et un serveur VNC **protégé par ce mot de passe d'essai jetable** (le
passer en argument le montre dans l'historique du shell : n'utilisez jamais un vrai mot de passe). Pour vérifier que le texte tapé
arrive bien au serveur, lancer dans le conteneur un `xterm` plein écran (`-geometry 150x45+0+0 -e sh -c "cat > /tmp/typed.txt"`)
puis lire `/tmp/typed.txt`. Code de retour 1 si une trace est trouvée. Bibliothèque standard seulement."""
import argparse, os, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import reference_session as r


def pieces(s):
    return [s] + [s[i:i + 4] for i in range(len(s) - 3)]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", required=True)
    ap.add_argument("--port", type=int, default=5900)
    ap.add_argument("--password", required=True, help="mot de passe d'essai jetable du serveur VNC (8 caractères alphanumériques)")
    ap.add_argument("--typed", default="Wm3pLzRj9", help="texte tapé sur la tablette après la connexion (alphanumérique : adb input text)")
    a = ap.parse_args()

    r.ensure_screen_on()
    r.set_display_settings(perf=True, fit=False)
    r.set_encoding_mode("auto")
    for b in ("main", "system", "events", "radio"):
        r.adb("logcat", "-b", b, "-c", check=False)

    r.sh(f"am force-stop {r.PKG}"); time.sleep(1)
    r.sh(f"am start -n {r.PKG}/.MainActivity"); time.sleep(2)
    if r.find("Reconnecter"):
        r.tap(*r.find("Reconnecter")); time.sleep(2)
    else:
        r.tap(*r.find("Ajouter", "Button")); time.sleep(2)
    for index, value in ((1, a.host), (2, str(a.port)), (3, a.password)):
        r.hide_keyboard()  # le clavier logiciel décale la mise en page
        current, x, y = [(t, x, y) for t, c, x, y in r.ui_nodes() if c == "EditText"][index][0:3]
        r.tap(x, y)
        if current and index != 3:
            r.sh("input keyevent KEYCODE_MOVE_END")
            for _ in range(len(current) + 2):
                r.sh("input keyevent KEYCODE_DEL")
        r.sh(f"input text {value}")
    r.sh("uiautomator dump /sdcard/pw.xml")
    tree = r.sh("cat /sdcard/pw.xml"); r.sh("rm -f /sdcard/pw.xml")
    r.hide_keyboard()
    r.tap(*r.find("Connexion", "Button"))
    for _ in range(20):
        time.sleep(1)
        if r.focus() == "RemoteActivity":
            break
    screen = r.focus()
    if screen != "RemoteActivity":
        raise SystemExit(f"la connexion n'a pas abouti (écran : {screen}) : la vérification ne prouverait rien")
    time.sleep(4)
    r.tap(640, 400); time.sleep(1)                 # le focus X suit le pointeur distant
    r.sh(f"input text {a.typed}"); time.sleep(1.5)
    r.sh("input keyevent KEYCODE_ENTER"); time.sleep(2)

    logs = "\n".join(r.adb("logcat", "-b", b, "-d", check=False) for b in ("main", "system", "events", "radio"))
    print(f"lignes de journal examinées : {len(logs.splitlines())} (dont {logs.count('SecondScreenPerf')} lignes de mesures)")
    found = False
    for name, secret in (("mot de passe", a.password), ("texte tapé", a.typed)):
        hits = [p for p in pieces(secret) if p in logs]
        print(f"{name} dans le journal : {hits or 'AUCUN'}")
        found |= bool(hits)
    print(f"mot de passe dans l'arbre uiautomator pendant la saisie : {'OUI' if a.password in tree else 'non'}")
    found |= a.password in tree
    sys.exit(1 if found else 0)


if __name__ == "__main__":
    main()
