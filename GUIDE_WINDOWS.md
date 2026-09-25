# Guide Windows (SS-092)

Procédure pour exposer un écran virtuel 1280×800 depuis un PC Windows 10/11 et le servir en VNC à la tablette.

**Avertissement honnête : rien de ce guide n'a été exécuté ni vérifié** — aucune machine Windows n'est disponible dans l'environnement où ce projet est développé. C'est une synthèse de techniques et d'outils publiquement documentés, présentée avec les mêmes réserves que PC_SETUP.md pour le pare-feu Windows. Merci de signaler ce qui ne correspond pas à la réalité une fois essayé, pour corriger ce document.

## 1. Créer l'écran virtuel

Windows ne propose pas nativement de créer un moniteur factice. La solution la plus citée et activement maintenue est un pilote **Indirect Display Driver** (IddCx), par exemple **Virtual Display Driver** ([github.com/itsmikethetech/Virtual-Display-Driver](https://github.com/itsmikethetech/Virtual-Display-Driver)) :

1. Télécharger la dernière version depuis la page *Releases* du projet.
2. Le pilote n'est en général pas signé par un éditeur reconnu de Windows : certaines versions nécessitent d'activer le **mode test** avant l'installation :
   ```powershell
   bcdedit /set testsigning on
   ```
   puis redémarrer. (Les versions les plus récentes du projet peuvent fournir un pilote signé qui évite cette étape ; vérifier le README du projet au moment de l'installation.)
3. Installer le pilote (script ou exécutable fourni par le projet), puis **redémarrer**.
4. Éditer son fichier de configuration (le nom exact et le format — JSON, YAML ou XML selon la version — sont donnés dans le README du projet, susceptibles de changer) pour déclarer un moniteur de résolution **1280×800**, puis redémarrer le service du pilote ou la session.
5. **Paramètres → Système → Affichage** doit maintenant montrer un moniteur supplémentaire. Choisir **« Étendre ces affichages »**, jamais « Dupliquer » (PC_SETUP.md). Le placer où on veut (à droite du principal, par exemple) : sa position ne change rien pour le VNC, seule sa **résolution** compte.
6. Mettre la **mise à l'échelle à 100 %** pour ce moniteur (clic sur le moniteur dans Paramètres → Affichage → Mise à l'échelle) : à une échelle différente, ce que le serveur VNC capture ne fait plus 1280×800 pixels réels.

## 2. Servir cet écran en VNC

Le point le plus incertain de ce guide : la plupart des serveurs VNC Windows partagent **par défaut tout le bureau virtuel** (tous les moniteurs réunis en un seul grand rectangle), pas un moniteur choisi. Pour n'envoyer que les 1280×800 du moniteur virtuel, il faut un serveur qui sache soit **choisir un moniteur précis**, soit **restreindre à une zone/coordonnées**.

- **UltraVNC** ([uvnc.com](https://uvnc.com)) est le candidat le plus souvent cité pour la gestion multi-écrans côté serveur Windows : ouvrir ses propriétés d'administration (icône dans la zone de notification → *Admin Properties*) et chercher une option de sélection d'écran/moniteur (« Screen capture », « Poll » ou équivalent selon la version) ; sélectionner le moniteur virtuel.
- **TightVNC** ([tightvnc.com](https://www.tightvnc.com)) est plus simple à installer mais, à notre connaissance, ne propose pas de sélection de moniteur dans sa version gratuite classique — à vérifier sur la version actuelle avant de l'écarter.
- Si aucune option de ce genre n'est trouvée dans le serveur choisi, chercher une option de **capture par coordonnées/rectangle** (« capture region », parfois accessible en argument de ligne de commande) et y indiquer la position exacte du moniteur virtuel (visible dans **Paramètres → Affichage**, ou via `Get-CimInstance -Namespace root/wmi -ClassName WmiMonitorBasicDisplayParams` en PowerShell pour lister les moniteurs).

Une fois configuré, définir un **mot de passe VNC** (jamais de serveur sans mot de passe exposé sur le réseau, voir SECURITY.md) et vérifier avec un client VNC classique sur le PC lui-même (`localhost`) que ce qui s'affiche fait bien 1280×800 et correspond au moniteur virtuel, avant de passer à la tablette.

## 3. Pare-feu et réseau

Voir PC_SETUP.md, section « Pare-feu (SS-073) » : règle limitée au sous-réseau du LAN, profil réseau **Privé** uniquement, jamais de redirection sur le routeur.

## Dépannage

- **Le moniteur virtuel n'apparaît pas après l'installation du pilote** : vérifier le mode test (`bcdedit /enum` doit montrer `testsigning Yes`), redémarrer complètement (pas juste fermer une session), consulter le *Gestionnaire de périphériques* pour une erreur sur le pilote (icône d'avertissement).
- **L'écran virtuel s'éteint ou repasse en veille** : Windows peut couper un moniteur qu'il croit inutilisé ; désactiver la mise en veille de l'affichage (**Paramètres → Système → Alimentation**), ou consulter les options du pilote virtuel lui-même (certains ont un réglage pour rester actif).
- **L'image VNC est floue ou décalée** : mise à l'échelle du moniteur virtuel différente de 100 % (étape 1.6) ; ou le serveur VNC redimensionne l'image au lieu de la recadrer — chercher une option « pas de mise à l'échelle »/« taille réelle ».
- **Rien ne s'affiche dans la zone attendue** : le serveur VNC partage encore tout le bureau virtuel, pas le moniteur choisi — revoir l'étape 2 ; en dernier recours, déplacer le moniteur virtuel en position (0,0) et l'écran physique existant à sa droite, puis partager les 1280×800 du coin supérieur gauche si le serveur propose un recadrage par coordonnées plutôt que par nom de moniteur.
- Pour les problèmes de pare-feu : voir PC_SETUP.md.
