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

## Hors LAN

Si un accès distant est ajouté, utiliser une couche sécurisée externe (VPN/tunnel) plutôt que d'inventer un chiffrement applicatif.
