# Configuration côté PC

## Principe

Le client Android n'invente pas un écran. Le PC doit disposer :

1. d'un écran virtuel 1280×800 réellement reconnu par l'OS ;
2. d'un serveur VNC capable d'exposer cet écran ;
3. d'un pare-feu limitant l'accès au LAN de confiance.

## Windows

Candidat : Virtual Display Driver ou autre pilote open source maintenu permettant de créer un écran 1280×800. Ensuite sélectionner un serveur VNC open source et vérifier qu'il peut capturer le moniteur virtuel voulu.

Critère : Windows doit être en mode **Étendre ces affichages**, jamais en duplication.

## Ubuntu

La solution dépend de la session graphique (Xorg/Wayland) et du GPU. Le projet doit documenter une configuration reproductible qui crée un véritable espace d'affichage 1280×800. Un simple bureau VNC indépendant n'est pas équivalent à un deuxième écran étendu.

## Réseau

- tablette et PC sur le même LAN ;
- IP fixe/réservation DHCP recommandée pour le PC ;
- port VNC accessible uniquement depuis le LAN ;
- ne pas faire de redirection NAT du port 5900.
