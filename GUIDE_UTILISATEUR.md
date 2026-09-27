# Mode d'emploi utilisateur — SecondScreen

SecondScreen transforme une tablette Android, notamment la Samsung Galaxy Tab 2 10.1 GT-P5110 sous Android 4.2.2, en écran secondaire pour un ordinateur du réseau local. L'ordinateur fournit l'image au moyen d'un serveur VNC ; la tablette affiche cet écran et renvoie les actions tactiles et clavier.

Ce guide s'adresse à l'utilisateur final. Les réglages techniques détaillés du PC sont dans `PC_SETUP.md`, `GUIDE_UBUNTU.md`, `GUIDE_MX_LINUX.md` et `GUIDE_WINDOWS.md`.

## 1. À savoir avant de commencer

Vous avez besoin de :

- une tablette Android compatible, idéalement en 1280 × 800 ;
- l'application SecondScreen installée sur la tablette ;
- un PC Ubuntu, MX Linux ou Windows connecté au même réseau local que la tablette ;
- un écran virtuel 1280 × 800 configuré sur le PC ;
- un serveur VNC lancé sur le PC, de préférence protégé par mot de passe ;
- l'adresse IP ou le nom réseau du PC ;
- le port VNC utilisé, souvent `5900` ;
- le mot de passe VNC, si le serveur en demande un.

Important : VNC classique n'est pas chiffré. Le mot de passe et l'image de l'écran peuvent circuler en clair sur le réseau. Utilisez SecondScreen uniquement sur un réseau local de confiance. N'exposez jamais le port VNC sur Internet et n'activez pas de redirection de port sur la box ou le routeur.

## 2. Installer l'application sur la tablette

SecondScreen n'est pas publié sur un magasin d'applications. Il faut installer l'APK manuellement.

1. Récupérez le fichier APK fourni par le projet ou compilé depuis les sources.
2. Sur la tablette, ouvrez `Paramètres` → `Sécurité`.
3. Activez `Sources inconnues` pour autoriser l'installation d'un APK hors magasin.
4. Copiez l'APK sur la tablette, ou installez-le par USB avec `adb install -r app-debug.apk`.
5. Ouvrez l'APK depuis la tablette et confirmez l'installation.
6. Lancez l'application `SecondScreen` depuis le tiroir d'applications.

L'avertissement Android sur les sources inconnues est normal pour une installation manuelle. Vous pouvez désactiver `Sources inconnues` après l'installation si vous le souhaitez.

## 3. Préparer le PC

Avant de connecter la tablette, le PC doit déjà envoyer un écran par VNC.

### Sur Ubuntu ou MX Linux

Deux usages sont possibles :

- un vrai bureau étendu, quand le PC a déjà un écran et que vous voulez faire glisser des fenêtres vers la tablette ;
- un écran virtuel isolé, utile pour tester ou pour un serveur sans écran.

Suivez `GUIDE_UBUNTU.md` pour Ubuntu ou `GUIDE_MX_LINUX.md` pour MX Linux. Le point essentiel est que le serveur VNC doit exposer une zone de 1280 × 800, et pas tout le bureau si celui-ci est plus grand.

### Sur Windows

Windows a généralement besoin d'un pilote d'écran virtuel, puis d'un serveur VNC capable de partager ce moniteur ou une zone précise. Suivez `GUIDE_WINDOWS.md`.

Dans les paramètres d'affichage Windows, choisissez `Étendre ces affichages`, pas `Dupliquer`.

### Dans tous les cas

- Le PC et la tablette doivent être sur le même réseau local.
- Le pare-feu du PC doit autoriser le port VNC uniquement depuis le réseau local.
- Le port par défaut est `5900`, mais votre serveur peut utiliser un autre port.
- Notez l'adresse IP du PC, par exemple `192.168.1.10`.
- Vérifiez si le serveur VNC demande un mot de passe.

## 4. Créer une connexion dans SecondScreen

1. Ouvrez `SecondScreen` sur la tablette.
2. Sur l'écran d'accueil, appuyez sur `Ajouter`.
3. Lisez l'avertissement de sécurité sur la connexion non chiffrée.
4. Renseignez les champs :
   - `Nom` : un nom libre, par exemple `PC du bureau` ;
   - `Adresse du PC` : l'adresse IP ou le nom réseau du PC ;
   - `Port` : généralement `5900` ;
   - `Mot de passe VNC` : le mot de passe du serveur, ou laissez vide si le serveur n'en demande pas.
5. Laissez `Se reconnecter automatiquement` coché si vous voulez que la tablette réessaie en cas de coupure réseau.
6. Laissez `Enregistrer cette connexion` coché si vous voulez retrouver ce PC dans la liste plus tard.
7. Appuyez sur `Connexion`.

Le mot de passe VNC n'est pas enregistré. Il faut le saisir à chaque nouvelle connexion. Si la reconnexion automatique est activée, le mot de passe peut rester temporairement en mémoire pendant la session en cours, mais il n'est pas sauvegardé dans le profil.

## 5. Utiliser l'écran distant

Quand la connexion réussit, l'écran du PC apparaît sur la tablette.

### Afficher ou masquer la barre de commandes

La barre de commandes est masquée pendant l'utilisation normale.

Pour l'afficher :

- appuyez sur la touche `Retour` de la tablette ;
- ou faites un tap à trois doigts sur l'écran distant.

Quand la barre est affichée, un nouvel appui sur `Retour` quitte l'écran distant et revient à la liste des connexions.

### Commandes tactiles

En mode pointeur direct :

- toucher l'écran correspond à viser directement ce point de l'écran distant ;
- un tap envoie un clic gauche ;
- un glissement déplace avec le bouton maintenu ;
- un appui long envoie un clic droit ;
- deux doigts servent au défilement ;
- trois doigts affichent ou masquent la barre de commandes.

En mode touchpad :

- le doigt agit comme sur un pavé tactile ;
- glissez pour déplacer le pointeur ;
- touchez pour cliquer ;
- touchez puis glissez pour faire un glisser-déposer ;
- faites un appui long pour le clic droit ;
- utilisez deux doigts pour défiler.

Le bouton `Pointeur : direct` / `Pointeur : touchpad` permet de changer de mode. En mode touchpad, un réglage de sensibilité apparaît dans la barre.

## 6. Utiliser le clavier

Appuyez sur `Clavier` dans la barre de commandes pour ouvrir le clavier virtuel Android.

Une rangée de touches spéciales apparaît également :

- `Échap` ;
- `Tab` ;
- `Ctrl` ;
- `Alt` ;
- `Maj` ;
- `Suppr` ;
- `Effacer` ;
- `Entrée` ;
- flèches directionnelles ;
- `Masquer`.

`Ctrl`, `Alt` et `Maj` fonctionnent comme des modificateurs pour la prochaine frappe. Le bouton `Masquer` quitte le mode clavier. Un clavier physique USB ou Bluetooth peut aussi envoyer des touches au PC pendant la session.

## 7. Régler l'affichage

La barre de commandes contient un bouton d'échelle.

- `Échelle : 1:1` : chaque pixel du PC correspond à un pixel de la tablette. C'est le mode le plus net lorsque le serveur est en 1280 × 800.
- `Échelle : ajustée` : l'image tient entièrement dans l'écran, avec éventuellement des bandes noires. Le texte peut être un peu moins net.
- `Échelle : ajustée (auto)` : SecondScreen ajuste automatiquement l'image parce que le serveur n'envoie pas la résolution nominale 1280 × 800.

Le bouton `Plein écran` masque la barre système Android pour utiliser toute la surface. Sur Android 4.2, le premier toucher après quelques secondes d'inactivité peut être perdu lorsque le plein écran est actif. C'est une limitation connue du système.

## 8. Reconnexion et déconnexion

Si le réseau coupe ou si le PC se met en veille, SecondScreen affiche un panneau d'état.

Selon le réglage choisi :

- l'application peut tenter une reconnexion automatique ;
- vous pouvez appuyer sur `Réessayer maintenant` ;
- vous pouvez appuyer sur `Arrêter` pour stopper les tentatives automatiques ;
- vous pouvez appuyer sur `Reconnecter` pour relancer manuellement ;
- si le mot de passe n'est plus en mémoire, l'application le redemande.

Pour quitter volontairement la session, affichez la barre de commandes puis appuyez sur `Déconnexion`.

Quand vous quittez l'écran distant pour revenir à l'accueil, la session VNC est fermée : il n'y a pas de connexion réseau en arrière-plan. L'ouverture du diagnostic depuis la barre ne ferme pas la session.

## 9. Gérer les connexions enregistrées

Sur l'écran d'accueil :

- appuyez sur une connexion enregistrée pour la rouvrir ;
- appuyez sur `Reconnecter : ...` pour reprendre le dernier profil utilisé ;
- faites un appui long sur une connexion pour la supprimer ;
- appuyez sur `Ajouter` pour créer une autre connexion ;
- appuyez sur `Diagnostic` pour voir les informations de la tablette.

La suppression d'une connexion dans SecondScreen ne modifie pas le PC ni son serveur VNC.

## 10. Diagnostic

L'écran `Diagnostic` affiche des informations utiles pour comprendre le comportement de l'application :

- version Android et niveau API ;
- résolution de l'écran ;
- mémoire disponible ;
- architecture CPU ;
- encodage VNC demandé au serveur ;
- option d'affichage des mesures de performance.

En usage normal, laissez l'encodage sur `Automatique (Hextile, CopyRect, RAW)`. Le mode `RAW seul` est surtout utile pour comparer ou diagnostiquer : il peut consommer beaucoup plus de Wi-Fi et de CPU.

L'option de mesures de performance affiche des chiffres sur l'écran distant et écrit une ligne de mesures par seconde dans le journal. Elle est désactivée par défaut car elle coûte un peu de temps processeur.

## 11. Dépannage rapide

### Adresse introuvable

Vérifiez l'adresse IP ou le nom du PC. Essayez avec l'adresse IP plutôt qu'un nom réseau.

### Le PC ne répond pas

Vérifiez que :

- le PC est allumé ;
- la tablette est connectée au même Wi-Fi ou au même réseau local ;
- le serveur VNC est lancé ;
- le pare-feu autorise le port depuis le réseau local ;
- le PC n'est pas en veille.

### Connexion refusée

Le PC répond, mais aucun serveur VNC n'écoute sur ce port. Vérifiez le numéro de port et redémarrez le serveur VNC.

### Ce port ne répond pas comme un serveur VNC

Le port saisi n'est probablement pas celui du serveur VNC. Vérifiez la configuration du serveur.

### Mot de passe refusé

Retapez le mot de passe VNC. Attention aux majuscules, aux claviers différents et aux caractères spéciaux.

### Authentification non prise en charge

Le serveur VNC utilise un mode de sécurité que SecondScreen ne prend pas en charge. Réglez le serveur sur VNC classique avec aucun mot de passe ou avec mot de passe VNC. Pour un usage réel, préférez un mot de passe.

### L'image est noire

Vérifiez que l'écran virtuel du PC affiche réellement quelque chose. Sur Ubuntu avec un écran virtuel isolé, il faut lancer une application sur le bon affichage, par exemple avec `DISPLAY=:1`.

### L'image montre le mauvais écran ou seulement un morceau du bureau

Le serveur VNC partage probablement tout le bureau ou une mauvaise zone. Il faut le limiter au moniteur virtuel ou à un rectangle 1280 × 800 correspondant à l'écran destiné à la tablette.

### L'image est floue ou déformée

Vérifiez que le serveur envoie bien 1280 × 800 et que le PC n'applique pas une mise à l'échelle. Sur Windows, mettez l'échelle du moniteur virtuel à 100 %.

### Le bas de l'image manque

En mode `Échelle : 1:1`, si la barre système Android reste visible, les dernières lignes peuvent être masquées. Activez `Plein écran` ou utilisez `Échelle : ajustée`.

## 12. Bonnes pratiques

- Utilisez un mot de passe VNC.
- Gardez VNC limité au réseau local.
- Ne redirigez jamais le port VNC vers Internet.
- Préférez une résolution serveur de 1280 × 800.
- Fermez la session avec `Déconnexion` quand vous avez fini.
- Si la connexion devient lente, rapprochez la tablette du point Wi-Fi ou testez un encodage différent dans le diagnostic.
- Laissez les mesures de performance désactivées sauf en phase de test.

## 13. Résumé express

1. Configurez sur le PC un écran virtuel 1280 × 800 et un serveur VNC.
2. Notez l'adresse IP du PC, le port VNC et le mot de passe.
3. Ouvrez SecondScreen sur la tablette.
4. Appuyez sur `Ajouter`.
5. Saisissez nom, adresse, port et mot de passe.
6. Appuyez sur `Connexion`.
7. Utilisez `Retour` ou un tap à trois doigts pour afficher la barre de commandes.
8. Appuyez sur `Déconnexion` pour terminer la session.
