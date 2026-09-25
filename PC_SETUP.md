# Configuration côté PC

## Principe

Le client Android n'invente pas un écran. Le PC doit disposer :

1. d'un écran virtuel 1280×800 réellement reconnu par l'OS ;
2. d'un serveur VNC capable d'exposer cet écran ;
3. d'un pare-feu limitant l'accès au LAN de confiance.

## Windows

Voir **[GUIDE_WINDOWS.md](GUIDE_WINDOWS.md)** (SS-092) : pilote Virtual Display Driver, configuration à 1280×800, choix d'un serveur VNC qui restreint la capture à ce moniteur. **Non vérifié** (aucune machine Windows disponible pour ce projet), à confirmer à l'usage.

Critère : Windows doit être en mode **Étendre ces affichages**, jamais en duplication.

## Ubuntu

Voir **[GUIDE_UBUNTU.md](GUIDE_UBUNTU.md)** (SS-091) : deux procédures reproductibles, une pour étendre un vrai bureau existant (sortie `VIRTUAL1` via `xrandr`, non vérifiée faute de GPU dans l'environnement de développement), une pour un écran virtuel isolé sans bureau existant (pilote `dummy` + `x11vnc --clip`, entièrement vérifiée : dimensions et contenu exacts, découpe fidèle au pixel près). Un simple bureau VNC indépendant n'est pas équivalent à un deuxième écran étendu — voir les limites de chaque méthode dans le guide.

## Réseau

- tablette et PC sur le même LAN ;
- IP fixe/réservation DHCP recommandée pour le PC ;
- port VNC accessible uniquement depuis le LAN ;
- **ne jamais faire de redirection NAT/port forwarding/DMZ du port 5900** sur le routeur : ce serait exposer VNC classique (non chiffré, SS-072) directement sur Internet.

## Pare-feu (SS-073)

Le pare-feu du PC doit **autoriser le port VNC depuis le sous-réseau du LAN, et rien d'autre** : pas « n'importe quelle adresse », et pas de profil réseau public/invité. Remplacer `5900` et `192.168.1.0/24` par le port et le sous-réseau réels (`ip -4 addr` sous Ubuntu, `ipconfig` sous Windows donnent l'un et l'autre).

### Ubuntu (`ufw`)

```bash
sudo ufw allow from 192.168.1.0/24 to any port 5900 proto tcp comment 'SecondScreen VNC, LAN seulement'
sudo ufw enable      # si pas déjà actif ; le défaut d'ufw est de tout refuser en entrée sauf règle explicite
sudo ufw status verbose
```

`ufw status verbose` doit montrer la règle avec l'adresse source `192.168.1.0/24`, pas `Anywhere`. Sans `from <sous-réseau>`, `ufw allow 5900` ouvrirait le port à **toute** adresse qui atteint la machine (dont Internet si un routeur fait par ailleurs une redirection). Pour retirer la règle : `sudo ufw delete allow from 192.168.1.0/24 to any port 5900 proto tcp`.

### Windows (pare-feu Windows Defender)

Interface graphique : *Pare-feu Windows Defender avec fonctions avancées de sécurité* → *Règles de trafic entrant* → *Nouvelle règle* → **Port** → TCP, port local `5900` → **Autoriser la connexion** → cocher **uniquement** le profil **Privé** (décocher Domaine et Public) → à l'étape *Étendue*, sous « Adresses IP distantes », choisir **Ces adresses IP** et ajouter le sous-réseau du LAN (`192.168.1.0/24`) plutôt que « N'importe quelle adresse IP ».

Équivalent PowerShell (à exécuter en administrateur) :

```powershell
New-NetFirewallRule -DisplayName "SecondScreen VNC (LAN seulement)" -Direction Inbound -Protocol TCP -LocalPort 5900 `
    -Profile Private -RemoteAddress 192.168.1.0/24 -Action Allow
```

Vérifier : `Get-NetFirewallRule -DisplayName "SecondScreen VNC*" | Get-NetFirewallAddressFilter` doit montrer le sous-réseau dans `RemoteAddress`, pas `Any`. Pour retirer la règle : `Remove-NetFirewallRule -DisplayName "SecondScreen VNC (LAN seulement)"`.

### Dans tous les cas

- si la tablette se connecte parfois d'une autre pièce via un second point d'accès Wi-Fi (même LAN mais sous-réseau différent), élargir le sous-réseau autorisé (ou l'IP précise de la tablette) plutôt que d'ouvrir à toute adresse ;
- une règle de pare-feu limitée au LAN **ne protège pas** contre une redirection NAT faite par ailleurs sur le routeur (ne jamais l'activer, voir « Réseau » ci-dessus) ;
- pour un accès occasionnel depuis l'extérieur du LAN, utiliser un VPN vers le domicile (WireGuard, par exemple) plutôt qu'exposer le port ou lever la règle de pare-feu.
