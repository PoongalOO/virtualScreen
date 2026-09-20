# Sécurité

## Modèle de menace

L'application est destinée à un LAN domestique/de développement de confiance. RFB/VNC historique n'est pas considéré comme un protocole sûr à exposer directement à Internet.

## Règles

1. Ne jamais exposer directement TCP/5900 sur Internet.
2. Ne jamais journaliser le mot de passe.
3. Valider toutes les longueurs reçues avant allocation.
4. Valider tous les rectangles avant écriture framebuffer.
5. Limiter les dimensions maximales acceptées.
6. Fermer socket et ressources sur erreur.
7. Traiter le serveur comme une entrée non fiable même sur LAN.
8. Ne pas inclure de télémétrie ou analytics cloud.

## Secrets

Android 4.2.2 impose des limites importantes par rapport aux mécanismes modernes de stockage sécurisé. Pour le MVP, privilégier la saisie du mot de passe à chaque connexion. La mémorisation d'un mot de passe doit être une fonctionnalité séparée avec analyse de risque explicite.

Côté code (SS-014) : `VncAuthentication` reçoit le mot de passe en `CharArray` et **l'efface** dès la dérivation de la clé ; la clé est effacée après l'authentification ou à `close()`, le handler est à usage unique, et aucun journal, message d'exception ou `toString()` ne contient de secret. C'est un meilleur effort : la JVM et la couche JCA peuvent conserver des copies que l'on ne peut pas effacer. Ne jamais convertir le mot de passe en `String` (immuable, non effaçable) avant de le passer au handler.

### Mot de passe gardé en mémoire pour la reconnexion automatique (SS-055)

La règle ci-dessus (saisie à chaque connexion) souffre **une exception, choisie par l'utilisateur** : avec la reconnexion automatique, l'application garde une **copie du mot de passe en mémoire** pour se reconnecter seule après une coupure. Sans cela la reconnexion serait impossible pour tout serveur protégé.

Analyse de risque :
- **Jamais sur le stockage** : ni profil, ni préférences, ni journal, ni `Intent`, ni état d'instance ; `ConnectionSettings` ne contient que la case cochée ou non.
- **Durée limitée** : la copie vit tant que la reconnexion automatique est active pour la session en cours, c'est-à-dire tant que l'écran distant est affiché (la session se ferme quand il ne l'est plus, sans service en arrière-plan). Elle est **effacée de façon synchrone** par `disconnect()`, à l'arrêt manuel, à l'abandon des tentatives et en fin de session ; chaque tentative reçoit sa propre copie, effacée par `VncAuthentication` dès la clé dérivée.
- **Menace couverte** : lecture du stockage de l'application, sauvegarde, journaux. **Non couverte** : un attaquant qui lit la mémoire du processus pendant la session (appareil rooté, débogage) ; c'est déjà le cas d'un mot de passe saisi, la JVM et l'`EditText` pouvant en garder des copies non effaçables.
- **Divulgation** : la case est **cochée par défaut** et sa note le dit à côté du champ mot de passe (« il reste alors en mémoire jusqu'à la fin de la session, décochez pour qu'il soit effacé dès la connexion »). Décochée, ou pour un serveur sans mot de passe, rien n'est gardé.
- **Limite du protocole** : VNC Authentication est faible et non chiffré (voir plus haut) ; garder le mot de passe quelques minutes en mémoire n'ajoute pas de surface réseau.

Si cette exception n'est pas acceptable, décochez la case par défaut (`ConnectionSettings`, une constante) : la reconnexion redemandera alors le mot de passe (SS-054).

## Frappes au clavier

Tout ce qui est tapé sur la tablette part **non chiffré** vers le PC, comme le reste de la session VNC : un mot de passe saisi dans une application distante circule en clair sur le Wi-Fi. C'est la limite du protocole RFB classique sur un LAN de confiance, pas un défaut de l'application. L'application ne journalise, ne conserve ni n'analyse aucune frappe (`KeyboardInput`, `KeyboardInputView`).

## Hors LAN

Si un accès distant est ajouté, utiliser une couche sécurisée externe (VPN/tunnel) plutôt que d'inventer un chiffrement applicatif.
