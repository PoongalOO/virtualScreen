# Cahier des charges — SecondScreen GT-P5110

## 1. Contexte

La Samsung Galaxy Tab 2 10.1 GT-P5110 est une tablette Wi-Fi équipée d'un écran 1280×800. L'appareil cible exécute Android 4.2.2 (API 17). Le projet vise à prolonger sa durée de vie en l'utilisant comme moniteur secondaire d'un PC.

Le PC reste responsable de la création du véritable écran virtuel. L'application Android est un client RFB/VNC spécialisé : elle affiche exclusivement l'écran virtuel et renvoie les entrées utilisateur.

## 2. Objectifs

### 2.1 Objectif principal

Permettre d'utiliser la GT-P5110 comme deuxième écran 1280×800 d'un PC Ubuntu ou Windows sur un réseau local Wi-Fi.

### 2.2 Objectifs secondaires

- installation simple par APK/ADB ;
- connexion rapide à un PC mémorisé ;
- affichage plein écran sans chrome inutile ;
- faible empreinte mémoire ;
- interaction tactile utilisable comme souris ;
- clavier logiciel optionnel ;
- reconnexion après rupture Wi-Fi ;
- code maintenable et testable ;
- aucune dépendance à un service cloud.

## 3. Hors périmètre initial

- audio distant ;
- transfert de fichiers ;
- presse-papiers bidirectionnel avancé ;
- accès via Internet public ;
- streaming vidéo haute fréquence ;
- 60 FPS ;
- multi-écrans simultanés dans l'application ;
- serveur VNC intégré au PC ;
- création de l'écran virtuel par l'application Android.

## 4. Plateforme cible

- Samsung Galaxy Tab 2 10.1 GT-P5110 ;
- Android 4.2.2 ;
- API 17 minimum ;
- écran paysage 1280×800 ;
- architecture matérielle ancienne : optimisation mémoire/CPU obligatoire ;
- Wi-Fi local.

L'application doit déclarer explicitement `minSdk = 17`. Android 4.2/4.2.2 correspond à l'API 17.

## 5. Architecture fonctionnelle

```text
PC Ubuntu / Windows
        │
        ├── écran physique
        │
        └── écran virtuel 1280×800
                  │
             serveur VNC
                  │ RFB/TCP
                  │ LAN Wi-Fi
                  ▼
          GT-P5110 / application
                  │
            moteur RFB Kotlin
                  │
             framebuffer
                  │
             SurfaceView
```

## 6. Fonctionnalités

### F01 — Configuration d'une connexion

L'utilisateur peut renseigner :

- nom de la connexion ;
- adresse IPv4 ou nom d'hôte ;
- port, 5900 par défaut ;
- mot de passe VNC si le mode d'authentification le nécessite.

Les paramètres non sensibles peuvent être conservés localement. Le mot de passe ne doit pas être journalisé.

### F02 — Connexion RFB

Le client doit :

1. ouvrir une socket TCP ;
2. lire la bannière de version RFB ;
3. négocier une version supportée ;
4. négocier le type de sécurité ;
5. effectuer l'initialisation client/serveur ;
6. lire dimensions, pixel format et nom du bureau ;
7. demander les encodages supportés ;
8. demander les mises à jour framebuffer.

### F03 — Affichage

- résolution cible native : 1280×800 ;
- orientation paysage forcée pour le mode écran ;
- mode plein écran ;
- rendu sans mise à l'échelle lorsque le serveur expose exactement 1280×800 ;
- mise à l'échelle conservant le ratio si la taille diffère ;
- aucune allocation de gros objet par frame.

### F04 — Encodages RFB

MVP :

- RAW obligatoire ;
- CopyRect recommandé immédiatement après le MVP.

V1 :

- Hextile.

V2 éventuelle :

- ZRLE ou Tight uniquement après mesures démontrant leur intérêt sur la GT-P5110.

### F05 — Entrées tactiles

Deux modes sont prévus :

**Mode direct** : la position du doigt correspond directement à la position du pointeur distant.

**Mode touchpad** : le déplacement du doigt déplace relativement le pointeur.

Gestes :

- tap : clic gauche ;
- double tap : double clic ;
- appui long : clic droit ;
- glisser : déplacement ;
- glisser en maintenant : drag ;
- deux doigts verticalement : molette.

Le MVP peut commencer uniquement avec le mode direct, tap et drag.

### F06 — Clavier

- affichage volontaire du clavier Android ;
- caractères usuels ;
- touches spéciales : Ctrl, Alt, Shift, Tab, Esc, Backspace, Enter ;
- conversion en keysyms RFB ;
- aucun raccourci ne doit empêcher de quitter l'application.

### F07 — Reconnexion

En cas de rupture réseau :

- arrêt propre de la boucle de lecture ;
- message clair ;
- bouton Reconnecter ;
- reconnexion automatique optionnelle avec backoff borné.

### F08 — Profils

V1 : plusieurs profils de PC peuvent être enregistrés. Le dernier profil peut être reconnecté rapidement.

### F09 — Diagnostic

Un écran diagnostic doit pouvoir afficher :

- version RFB négociée ;
- dimensions distantes ;
- pixel format ;
- encodage actif ;
- nombre de mises à jour ;
- débit reçu ;
- FPS de rendu ;
- mémoire approximative ;
- latence estimée si mesurable.

Aucun secret ne doit apparaître dans les logs.

## 7. Exigences non fonctionnelles

### Performance

- démarrage < 5 s sur la tablette cible hors délai réseau ;
- connexion LAN normalement < 3 s ;
- interaction de bureau visée : 10–20 FPS sur changements modérés ;
- priorité à une latence perceptuelle basse plutôt qu'au FPS maximal ;
- mémoire applicative à maintenir aussi basse que possible, cible initiale < 80 Mio à mesurer sur matériel réel ;
- aucune fuite mémoire lors de 2 h de connexion.

Les seuils de performance sont des objectifs projet à valider par benchmark, pas des garanties matérielles.

### Fiabilité

- aucune opération réseau sur le thread UI ;
- fermeture systématique des sockets ;
- traitement défensif des longueurs et rectangles reçus ;
- rejet d'un rectangle sortant du framebuffer ;
- gestion des EOF/timeouts ;
- pas de crash sur paquet RFB invalide.

### Sécurité

- usage par défaut limité au LAN de confiance ;
- avertissement visible pour les connexions VNC non chiffrées ;
- aucun mot de passe dans Logcat ;
- taille des messages réseau validée avant allocation ;
- pas d'exposition de serveur sur la tablette ;
- documentation recommandant pare-feu/VPN/tunnel pour tout usage hors LAN.

### Maintenabilité

- séparation UI / protocole / rendu / entrées ;
- classes courtes ;
- protocole RFB testable hors UI ;
- commentaires concentrés sur les contraintes protocolaires ;
- pas de dépendance externe sans justification.

## 8. UX

Écran d'accueil minimal : liste des connexions et bouton Ajouter.

Écran connexion : hôte, port, mot de passe, bouton Connexion.

Écran distant : SurfaceView plein écran ; une zone ou un geste permet d'afficher une petite barre de commandes (clavier, mode pointeur, déconnexion, diagnostic).

Le design doit rester compatible avec Android Views classiques. Jetpack Compose est exclu.

## 9. Critères d'acceptation V1

La V1 est acceptée si :

1. l'APK s'installe et démarre sur la GT-P5110 Android 4.2.2 ;
2. la tablette se connecte à un serveur VNC Ubuntu et un serveur VNC Windows ;
3. un écran virtuel PC 1280×800 est affiché à la résolution native ;
4. RAW fonctionne ;
5. Hextile fonctionne ;
6. les mises à jour incrémentales fonctionnent ;
7. tap, déplacement, drag, clic droit et scroll fonctionnent ;
8. le clavier permet au minimum texte, Enter, Backspace, Tab, Esc, Ctrl et Alt ;
9. une déconnexion/reconnexion ne nécessite pas de relancer l'application ;
10. un test continu de 2 h ne révèle pas de fuite ou crash bloquant ;
11. aucun secret n'est écrit dans les logs ;
12. la documentation d'installation PC/tablette est reproductible.
