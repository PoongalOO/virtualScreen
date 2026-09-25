# Guide Ubuntu (SS-091)

Procédure reproductible pour exposer un écran virtuel 1280×800 depuis un PC Ubuntu et le servir en VNC à la tablette. Deux cas, selon ce que vous avez :

- **[A. Étendre un vrai bureau](#a-étendre-un-vrai-bureau-session-xorg)** : le PC a déjà un écran, vous voulez que le second (la tablette) s'ajoute comme une vraie extension, où l'on peut glisser des fenêtres. **Non vérifié dans cet environnement** (voir « Ce qui a été vérifié et ce qui ne l'a pas été » plus bas) : nécessite un vrai GPU, absent d'un conteneur de test.
- **[B. Écran virtuel isolé](#b-écran-virtuel-isolé-serveur-sans-tête-ou-simple-test)** : le PC n'a pas d'écran (serveur), ou vous voulez juste vérifier que l'application et le réseau fonctionnent avant de configurer A. **Entièrement vérifié** ci-dessous, reproductible tel quel.

Testé sur Ubuntu 22.04 LTS (paquets standards ; 24.04 LTS a les mêmes noms de paquets et un `xrandr`/`x11vnc` équivalents).

## A. Étendre un vrai bureau (session Xorg)

Ne fonctionne qu'en session **Xorg**, pas Wayland (GNOME sur Ubuntu 22.04+ démarre en Wayland par défaut). À l'écran de connexion, cliquer sur l'icône en engrenage à côté du champ de mot de passe et choisir **« Ubuntu sur Xorg »** avant de se connecter.

### 1. Repérer une sortie « VIRTUAL »

Les pilotes graphiques ouverts d'Ubuntu (`modesetting`, `intel`, `amdgpu` — pas le pilote propriétaire NVIDIA) exposent en général une ou plusieurs sorties factices en plus des vraies, justement prévues pour ce genre d'usage :

```bash
xrandr --query
```

Cherchez une ligne `VIRTUAL1` (ou `VIRTUAL2`, etc.) avec `disconnected` : c'est une sortie qui existe mais qui n'a rien de branché. Si aucune n'apparaît : GPU NVIDIA propriétaire (voir « Dépannage »), ou pilote qui ne l'expose pas.

### 2. Créer le mode 1280×800 et l'assigner

```bash
cvt 1280 800 60
# exemple de sortie : Modeline "1280x800_60.00"  83.50  1280 1344 1480 1680  800 801 804 828 -hsync +vsync
xrandr --newmode "1280x800_60.00" 83.50 1280 1344 1480 1680 800 801 804 828 -hsync +vsync
xrandr --addmode VIRTUAL1 1280x800_60.00
xrandr --output VIRTUAL1 --mode 1280x800_60.00 --right-of eDP-1
```

Remplacer `VIRTUAL1` par le nom réellement trouvé à l'étape 1, et `eDP-1` par le nom de l'écran principal (celui du PC : `xrandr --query` le donne, en général `eDP-1` sur un portable, `HDMI-1`/`DP-1` sur un fixe). `--right-of` la place à droite de l'écran principal, comme un vrai second écran : ouvrir **Paramètres → Écrans** doit maintenant la montrer, sélectionnable et déplaçable.

Cette sortie disparaît à la reconnexion/redémarrage : refaire les 3 commandes, ou les mettre dans un script lancé au démarrage de session.

### 3. Servir uniquement cette zone en VNC

`x11vnc` partage par défaut **tout l'écran X** (tous les moniteurs réunis), pas seulement l'extension. `--clip` restreint exactement à la zone voulue : la largeur, la hauteur, et le décalage (`+X+Y`) où `xrandr --query` dit que `VIRTUAL1` a été placée (par exemple `1920x0` si l'écran principal fait 1920 de large et que l'extension est `--right-of`).

```bash
sudo apt install x11vnc
x11vnc -display :0 -clip 1280x800+1920+0 -forever -shared -rfbauth <(vncpasswd -f <<< 'votre-mot-de-passe') -rfbport 5900
```

- `-clip LARGEURxHAUTEUR+X+Y` : **la seule partie qui compte pour SecondScreen** — sans elle le client recevrait tout le bureau, pas 1280×800.
- `-rfbauth <(vncpasswd -f <<< '...')` : authentification par mot de passe (recommandé, voir SECURITY.md) ; `vncpasswd` vient du paquet `tigervnc-tools` (`sudo apt install tigervnc-tools`) ou `x11vnc` sait aussi lire un fichier créé par sa propre commande `x11vnc -storepasswd`.
- `-forever` : ne se ferme pas après la première déconnexion (sinon un seul essai de la tablette).
- Vérifier `xrandr --query` **après** avoir lancé `x11vnc` si l'affichage semble décalé : un décalage `+X+Y` incorrect montre un bout du mauvais écran plutôt qu'une erreur explicite.

## B. Écran virtuel isolé (serveur sans tête, ou simple test)

Pas de vrai second écran ni de fenêtres à glisser depuis le bureau existant : un écran X **indépendant**, complet dès le départ, prévu pour un PC qui n'a pas d'écran du tout, ou pour vérifier rapidement que l'application, le réseau et le mot de passe fonctionnent. **Vérifié de bout en bout** : dimensions, contenu et découpe confirmés pixel par pixel dans un environnement isolé (voir plus bas).

### 1. Installer

```bash
sudo apt install xserver-xorg-core xserver-xorg-video-dummy x11-xserver-utils x11vnc tigervnc-tools
```

### 2. Déclarer l'écran factice

Créer `/etc/X11/xorg-secondscreen.conf` :

```text
Section "Device"
    Identifier  "DummyDevice"
    Driver      "dummy"
    VideoRam    256000
EndSection

Section "Monitor"
    Identifier  "DummyMonitor"
    HorizSync   5.0 - 1000.0
    VertRefresh 5.0 - 200.0
    Modeline "1280x800_60.00"  83.50  1280 1344 1480 1680  800 801 804 828  -HSync +Vsync
EndSection

Section "Screen"
    Identifier  "DummyScreen"
    Device      "DummyDevice"
    Monitor     "DummyMonitor"
    DefaultDepth 24
    SubSection "Display"
        Depth   24
        Modes   "1280x800_60.00"
        Virtual 1280 800
    EndSubSection
EndSection
```

### 3. Démarrer l'écran X et le serveur VNC

Sur un numéro d'affichage **différent** de celui du bureau existant (`:1`, pas `:0`) :

```bash
sudo Xorg -noreset -config /etc/X11/xorg-secondscreen.conf :1 &
DISPLAY=:1 x11vnc -forever -shared -rfbauth <(vncpasswd -f <<< 'votre-mot-de-passe') -rfbport 5900
```

Pas de `-clip` ici : l'écran ne contient déjà que les 1280×800 voulus, rien d'autre. `DISPLAY=:1 xterm` (ou n'importe quelle application) ouvre une fenêtre **sur cet écran-là**, invisible depuis le bureau habituel (numéro d'affichage différent) : c'est un vrai bureau, séparé, pas juste une image statique.

**Limite de cette méthode** : c'est un second bureau **indépendant**, pas un agrandissement du bureau existant — on ne peut pas faire glisser une fenêtre entre les deux d'un geste de souris continu (il faut la lancer directement avec `DISPLAY=:1`, ou utiliser `xdotool`/un script pour la déplacer d'un écran à l'autre). Pour un vrai bureau étendu, voir la partie A.

## Ce qui a été vérifié et ce qui ne l'a pas été

| Élément | Statut |
|---|---|
| Paquets (`xserver-xorg-video-dummy`, `x11vnc`, `tigervnc-tools`) : noms et installation | **vérifié**, Ubuntu 22.04 |
| Partie B (écran factice complet) : démarre exactement en 1280×800, contenu correct | **vérifié** : lu par un client RFB indépendant, dimensions et pixels exacts |
| `x11vnc -clip LARGEURxHAUTEUR+X+Y` : n'expose que la zone demandée, ni plus ni moins | **vérifié** : sur un écran de test 3200×1080 avec deux fenêtres, une à gauche (« bureau principal ») et une dans la zone découpée (« extension »), le flux VNC annonçait 1280×800 et ne contenait que la seconde fenêtre — 0 pixel de la première |
| Partie A (sortie `VIRTUAL1`, `--right-of`, vrai bureau étendu) | **non vérifié** : nécessite un vrai GPU (pilote `modesetting`/`intel`/`amdgpu`), absent d'un conteneur ; technique bien documentée par ailleurs pour cet usage, mais pas testée ici |

## Dépannage

- **Aucune sortie `VIRTUAL*` dans `xrandr --query`** : GPU NVIDIA avec le pilote propriétaire (ne l'expose généralement pas — essayer le pilote `nouveau` ouvert, ou utiliser la partie B) ; ou session Wayland active (repasser en « Ubuntu sur Xorg », voir en haut) ; ou pilote trop ancien.
- **`xrandr: cannot find mode 1280x800_60.00`** après `--addmode` : le nom du mode doit correspondre **exactement** à celui créé par `--newmode` (recopier tel quel la ligne donnée par `cvt`, guillemets compris).
- **Écran noir ou VNC qui n'affiche rien** : vérifier que quelque chose est réellement affiché sur `VIRTUAL1`/`:1` (`DISPLAY=:1 xterm` doit apparaître) — un moniteur RandR sans rien dessus reste noir, ce n'est pas une panne du serveur VNC.
- **Le flux VNC montre le mauvais morceau de l'écran** (partie A) : le décalage `+X+Y` du `-clip` ne correspond pas à la position réelle donnée par `xrandr --query` (`VIRTUAL1 connected 1280x800+X+Y`) ; recopier ces valeurs exactement.
- **`x11vnc: Couldn't open display :1`** : erreur d'ordre — `x11vnc` doit démarrer **après** que `Xorg` a fini de s'initialiser (2 à 3 secondes) ; ou `DISPLAY` mal exporté dans la commande.
- **Pare-feu et exposition réseau** : voir PC_SETUP.md, section « Pare-feu ». Ne jamais lancer `x11vnc` sans mot de passe (`-nopw`) en dehors d'un test isolé sans réseau : les exemples de ce guide utilisent tous `-rfbauth`.
