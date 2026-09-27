# Guide MX Linux

Procédure pour utiliser un PC sous **MX Linux** comme source d'écran secondaire 1280 × 800 pour SecondScreen.

MX Linux est basé sur Debian et utilise souvent Xfce avec une session Xorg. Les commandes sont donc très proches du guide Ubuntu, mais avec quelques précautions propres à MX Linux : gestion du pare-feu via MX Tools / gufw selon l'installation, services systemd parfois non utilisés par défaut, et sessions Xorg généralement déjà actives.

## Objectif

Le PC MX Linux doit fournir à la tablette :

1. un affichage 1280 × 800 ;
2. un serveur VNC exposant seulement cet affichage ou une zone 1280 × 800 ;
3. un accès réseau limité au LAN de confiance.

SecondScreen ne crée pas l'écran côté PC : il se connecte au serveur VNC déjà lancé.

## Choisir la méthode

Deux méthodes sont possibles.

### A. Bureau étendu réel

À utiliser si vous voulez que la tablette devienne un vrai second écran : vous pouvez déplacer des fenêtres du moniteur principal vers l'écran de la tablette.

Cette méthode dépend du pilote graphique. Elle fonctionne seulement si `xrandr` expose une sortie virtuelle comme `VIRTUAL1`, `VIRTUAL-1` ou équivalent.

### B. Écran virtuel isolé

À utiliser pour tester rapidement ou si votre pilote ne fournit pas de sortie virtuelle. Cette méthode crée un serveur X séparé en 1280 × 800, accessible en VNC.

Limite : ce n'est pas une extension continue du bureau principal. Les fenêtres doivent être lancées explicitement sur cet affichage virtuel.

## Préparer les paquets

Installez les outils nécessaires :

```bash
sudo apt update
sudo apt install x11-xserver-utils xcvt x11vnc tigervnc-tools xterm
```

`xcvt` fournit la commande `cvt` utilisée à l'étape 3 de la méthode A (`x11-xserver-utils` seul ne la fournit pas sur les versions récentes de Debian/Ubuntu — vérifié).

Pour la méthode B, ajoutez le pilote d'écran factice :

```bash
sudo apt install xserver-xorg-core xserver-xorg-video-dummy
```

Selon la version de MX Linux et les dépôts activés, `tigervnc-tools` peut fournir la commande `vncpasswd`. Si elle manque, vérifiez le nom du paquet avec :

```bash
apt search tigervnc
```

## Trouver l'adresse IP du PC

Sur le PC MX Linux :

```bash
ip -4 addr
```

Cherchez l'adresse de l'interface connectée au réseau local, par exemple `192.168.1.25`. C'est cette adresse qu'il faudra saisir dans SecondScreen.

## Méthode A — bureau étendu avec xrandr

### 1. Vérifier la session graphique

MX Linux Xfce utilise généralement Xorg. Vérifiez :

```bash
echo "$XDG_SESSION_TYPE"
```

La réponse attendue est `x11`. Si vous êtes en Wayland, cette méthode ne convient pas ; utilisez une session Xorg ou passez à la méthode B.

### 2. Repérer les sorties vidéo

```bash
xrandr --query
```

Cherchez :

- le nom de l'écran principal, par exemple `eDP-1`, `LVDS-1`, `HDMI-1` ou `DP-1` ;
- une sortie virtuelle disponible, par exemple `VIRTUAL1`, `VIRTUAL-1` ou similaire.

Exemple de ligne utile :

```text
VIRTUAL1 disconnected
```

Si aucune sortie virtuelle n'apparaît, passez à la méthode B.

### 3. Créer un mode 1280 × 800

```bash
cvt 1280 800 60
```

La commande affiche une ligne `Modeline`. Exemple :

```text
Modeline "1280x800_60.00"  83.50  1280 1344 1480 1680  800 801 804 828 -hsync +vsync
```

Ajoutez ensuite ce mode à xrandr. Recopiez les valeurs données par `cvt` si elles diffèrent de l'exemple :

```bash
xrandr --newmode "1280x800_60.00" 83.50 1280 1344 1480 1680 800 801 804 828 -hsync +vsync
xrandr --addmode VIRTUAL1 1280x800_60.00
```

Remplacez `VIRTUAL1` par le nom exact trouvé avec `xrandr --query`.

### 4. Placer l'écran virtuel à droite du principal

Exemple avec un écran principal nommé `eDP-1` :

```bash
xrandr --output VIRTUAL1 --mode 1280x800_60.00 --right-of eDP-1
```

Remplacez `eDP-1` par le nom réel de votre écran principal.

Vérifiez ensuite :

```bash
xrandr --query
```

Vous devez voir la sortie virtuelle connectée avec une position, par exemple :

```text
VIRTUAL1 connected 1280x800+1920+0
```

Notez le `+X+Y`, ici `+1920+0` : il sert au recadrage VNC.

### 5. Démarrer x11vnc sur cette zone seulement

Créez d'abord un fichier de mot de passe VNC :

```bash
mkdir -p ~/.vnc
x11vnc -storepasswd ~/.vnc/secondscreen.pass
chmod 600 ~/.vnc/secondscreen.pass
```

Lancez ensuite le serveur VNC. Remplacez `+1920+0` par la position réelle trouvée avec `xrandr --query` :

```bash
x11vnc -display :0 -clip 1280x800+1920+0 -forever -shared -rfbauth ~/.vnc/secondscreen.pass -rfbport 5900
```

Points importants :

- `-clip 1280x800+X+Y` doit correspondre exactement à la zone de l'écran virtuel ;
- sans `-clip`, x11vnc peut partager tout le bureau au lieu du seul écran de la tablette ;
- `-forever` évite que le serveur VNC s'arrête à la première déconnexion ;
- `-shared` autorise une reconnexion sans fermer immédiatement la session.

## Méthode B — écran virtuel isolé avec pilote dummy

Cette méthode crée un affichage X séparé `:1` en 1280 × 800. Elle est utile si `xrandr` ne propose pas de sortie `VIRTUAL*`.

### 1. Créer la configuration Xorg

Créez le fichier `/etc/X11/xorg-secondscreen.conf` :

```bash
sudo nano /etc/X11/xorg-secondscreen.conf
```

Contenu :

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

### 2. Créer un mot de passe VNC

```bash
mkdir -p ~/.vnc
x11vnc -storepasswd ~/.vnc/secondscreen.pass
chmod 600 ~/.vnc/secondscreen.pass
```

### 3. Démarrer l'écran virtuel

Dans un terminal :

```bash
sudo Xorg -noreset -config /etc/X11/xorg-secondscreen.conf :1
```

Laissez ce terminal ouvert. Si Xorg démarre correctement, il occupe le terminal.

Dans un deuxième terminal, lancez le serveur VNC :

```bash
DISPLAY=:1 x11vnc -forever -shared -rfbauth ~/.vnc/secondscreen.pass -rfbport 5900
```

Pour vérifier que l'écran n'est pas vide, ouvrez une application dessus depuis un troisième terminal :

```bash
DISPLAY=:1 xterm
```

La fenêtre `xterm` apparaît sur l'écran virtuel envoyé à la tablette, pas sur votre bureau principal.

## Autoriser le port dans le pare-feu MX Linux

Le port VNC doit être accessible depuis le réseau local, mais jamais depuis Internet.

### Avec gufw / pare-feu graphique

Si vous utilisez l'outil graphique de pare-feu :

1. ouvrez l'outil de pare-feu MX Linux ;
2. activez le pare-feu si nécessaire ;
3. ajoutez une règle entrante TCP pour le port `5900` ;
4. limitez la source au sous-réseau local si l'interface le permet, par exemple `192.168.1.0/24` ;
5. n'autorisez pas le port sur un profil public ou invité.

### Avec ufw en ligne de commande

Si `ufw` est disponible :

```bash
sudo ufw allow from 192.168.1.0/24 to any port 5900 proto tcp comment 'SecondScreen VNC, LAN seulement'
sudo ufw status verbose
```

Remplacez `192.168.1.0/24` par votre vrai sous-réseau local. Pour le trouver, regardez l'adresse du PC avec `ip -4 addr`. Si le PC est `192.168.1.25`, le sous-réseau domestique courant est souvent `192.168.1.0/24`.

La sortie ne doit pas indiquer `Anywhere` pour le port VNC. Si vous voyez une règle trop large, supprimez-la et recréez une règle limitée au LAN.

Pour supprimer la règle :

```bash
sudo ufw delete allow from 192.168.1.0/24 to any port 5900 proto tcp
```

## Connexion depuis la tablette

Dans SecondScreen :

1. appuyez sur `Ajouter` ;
2. choisissez un nom, par exemple `MX Linux` ;
3. saisissez l'adresse IP du PC ;
4. saisissez le port `5900` ;
5. saisissez le mot de passe VNC créé avec `x11vnc -storepasswd` ;
6. appuyez sur `Connexion`.

## Script pratique pour la méthode A

Après validation manuelle, vous pouvez créer un petit script. Exemple à adapter :

```bash
#!/bin/sh
set -eu

VIRTUAL_OUTPUT="VIRTUAL1"
MAIN_OUTPUT="eDP-1"
MODE="1280x800_60.00"
CLIP="1280x800+1920+0"
PASS="$HOME/.vnc/secondscreen.pass"

xrandr --newmode "$MODE" 83.50 1280 1344 1480 1680 800 801 804 828 -hsync +vsync 2>/dev/null || true
xrandr --addmode "$VIRTUAL_OUTPUT" "$MODE" 2>/dev/null || true
xrandr --output "$VIRTUAL_OUTPUT" --mode "$MODE" --right-of "$MAIN_OUTPUT"
exec x11vnc -display :0 -clip "$CLIP" -forever -shared -rfbauth "$PASS" -rfbport 5900
```

À adapter impérativement :

- `VIRTUAL_OUTPUT` ;
- `MAIN_OUTPUT` ;
- `CLIP`.

Ne lancez pas ce script avant d'avoir vérifié les commandes à la main.

## Dépannage

### `xrandr` ne montre aucune sortie VIRTUAL

Votre pilote graphique n'expose probablement pas de sortie virtuelle. Utilisez la méthode B avec le pilote `dummy`.

### `xrandr --addmode` refuse le mode

Le nom du mode doit être exactement celui créé par `xrandr --newmode`. Si vous relancez la commande et que le mode existe déjà, l'erreur peut être sans gravité ; continuez avec `xrandr --addmode` ou vérifiez avec `xrandr --query`.

### La tablette affiche le mauvais morceau du bureau

Le `-clip 1280x800+X+Y` ne correspond pas à la position réelle de l'écran virtuel. Relancez :

```bash
xrandr --query
```

Recopiez exactement les coordonnées indiquées pour la sortie virtuelle.

### La tablette affiche un écran noir

Avec la méthode A, vérifiez qu'une fenêtre est bien placée sur l'écran virtuel.

Avec la méthode B, lancez une application sur l'affichage `:1` :

```bash
DISPLAY=:1 xterm
```

### `x11vnc` dit qu'il ne peut pas ouvrir l'affichage

Vérifiez le numéro d'affichage :

- bureau principal : souvent `:0` ;
- écran dummy de la méthode B : `:1`.

Pour la méthode B, démarrez `Xorg :1` avant `x11vnc`.

### La tablette ne se connecte pas

Vérifiez :

- l'adresse IP du PC ;
- le port VNC ;
- le mot de passe ;
- le pare-feu ;
- que la tablette et le PC sont sur le même réseau local ;
- que le PC n'a pas basculé en veille.

### Le port 5900 est déjà utilisé

Vérifiez :

```bash
ss -ltnp | grep ':5900'
```

Arrêtez l'autre serveur VNC ou utilisez un autre port, par exemple `5901`, puis saisissez ce même port dans SecondScreen.

## Sécurité

- Utilisez un mot de passe VNC.
- Ne lancez pas x11vnc avec `-nopw` sur un réseau actif.
- Limitez le pare-feu au LAN.
- Ne faites aucune redirection de port VNC sur la box Internet.
- Pour accéder depuis l'extérieur, utilisez un VPN vers le réseau local plutôt qu'une exposition directe du port VNC.

## Ce qui reste à vérifier sur une vraie installation MX Linux

Ce guide adapte les procédures Debian/Ubuntu et les outils standards Xorg/x11vnc à MX Linux. Les points à confirmer selon votre machine sont :

- le nom exact des sorties `xrandr` ;
- la présence ou non d'une sortie `VIRTUAL*` ;
- le gestionnaire de pare-feu réellement utilisé ;
- le comportement du pilote graphique, surtout avec NVIDIA propriétaire.

La méthode B est la plus indépendante du pilote graphique, mais elle fournit un écran isolé plutôt qu'un vrai bureau étendu.
