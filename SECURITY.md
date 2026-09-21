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

## Validation des données réseau (SS-070)

Revue transverse de **chaque valeur que le serveur peut fixer**. Le principe : une valeur venue du réseau n'est jamais utilisée pour allouer, boucler ou indexer **avant** d'avoir été bornée ; les sommes se font en `Long` ou par soustraction ; les entiers non signés sont lus comme tels (`U32` en `Long`, jamais en `Int` signé) ; tout refus est une erreur **typée** (`RfbProtocolException` ou `RfbTransportException`, toutes deux des `IOException`) qui ferme la socket.

| Valeur (largeur sur le fil) | Borne | Où | Refus |
|---|---|---|---|
| Bannière de version (12 octets) | `RFB 003.nnn\n` avec des chiffres ; majeure 3 et mineure ≥ 3, ou majeure supérieure (traitée comme 3.8) | `ProtocolVersion.parseServerBanner` | `InvalidBanner`, `UnsupportedVersion` |
| Nombre de types de sécurité, 3.8 (U8) | 0 à 255, borné par la largeur ; 0 = refus | `SecurityNegotiation.selectV38` | `ConnectionRejected` |
| Type de sécurité, 3.3 (U32) | lu en `Long`, comparé à nos types | `selectV33` | `NoSupportedSecurityType` |
| Longueur d'une raison de refus (U32) | **256 octets lus au plus**, quelle que soit la valeur annoncée ; texte assaini | `readReason` | (tronquée) |
| Résultat de sécurité (U32) | 0 ou 1 | `checkSecurityResult` | `InvalidSecurityResult` |
| Défi VNC (16 octets fixes) | longueur fixe | `VncAuthentication` | — |
| Largeur, hauteur de l'écran (U16) | 1 à 4 096 par axe **et** produit (en `Long`) ≤ 1 920 × 1 200 (budget mémoire, tas de 48 Mio) | `InitExchange.parseHeader` | `InvalidFramebufferSize` |
| Format de pixels (16 octets) | 8/16/32 bits, profondeur ≤ bits, maxima de la forme 2ⁿ−1, décalages contenus, composantes sans chevauchement | `PixelFormat.parse` | `InvalidPixelFormat` (le client impose ensuite son propre format) |
| Longueur du nom du bureau (U32) | ≤ 1 024, lu **avant** l'allocation ; assaini en ASCII imprimable | `InitExchange` | `InvalidDesktopName` |
| Type de message (U8) | 0 (mise à jour), 2 (Bell), 3 (texte) | `ServerMessageReader.readMessage` | `UnsupportedServerMessage` |
| Nombre de rectangles (U16) | **jamais utilisé pour allouer** : la lecture s'arrête à la fin du flux | `readFramebufferUpdate` | — |
| Rectangle x, y, largeur, hauteur (4 × U16) | tient dans l'écran, testé **par soustraction** (aucune addition qui déborde), **avant** toute lecture de pixels | `Framebuffer.contains`, chaque décodeur | `RectangleOutOfBounds` |
| Encodage (S32) | seulement Hextile, CopyRect, RAW (ceux qu'on annonce) ; pseudo-encodages refusés | `readFramebufferUpdate` | `UnsupportedEncoding` |
| RAW : pixels | une ligne à la fois dans un tampon de `largeur × 4` octets alloué une fois | `RawDecoder` | — |
| CopyRect : source (2 × U16) | doit tenir dans l'écran ; recouvrement source/destination géré | `CopyRectDecoder`, `Framebuffer.copyRect` | `RectangleOutOfBounds` (coordonnées de la source) |
| Hextile : sous-encodage (U8) | bits 5 à 7 interdits | `HextileDecoder` | `InvalidHextileTile` |
| Hextile : nombre de sous-rectangles (U8) | ≤ 255, tampon fixe de 255 × 6 octets | `drawSubrects` | — |
| Hextile : sous-rectangle | doit tenir dans **sa tuile**, pas seulement dans l'écran | `drawSubrects` | `InvalidHextileTile` |
| Longueur du texte du presse-papiers (U32) | ≤ 1 Mio, lu par blocs de 4 Kio et **jeté** (jamais stocké) | `skipCutText` | `CutTextTooLong` |
| Fin du flux, silence | `readFully` boucle sur les lectures partielles ; fin = `EndOfStream`, silence = `ReadTimeout` | `RfbSocket.readFully` | erreur de transport |

**Preuves** (package `rfb/robustness`, plus de 120 000 cas, ~5 s, graines fixes donc rejouables) :
- **Géométrie** : les trois encodages × toutes les combinaisons de 13 valeurs limites pour x, y, largeur, hauteur (85 683 cas). Un rectangle qui tient est dessiné **exactement** à sa place (CopyRect avec recouvrement compris) et nulle part ailleurs ; un rectangle refusé ne modifie **aucun pixel**. L'oracle est écrit à part, en `Long`.
- **Longueurs** : chaque longueur ci-dessus aux valeurs 0, 1, limite−1, limite, limite+1, `0x7FFFFFFF`, `0x80000000` (négatif si lu comme `Int`), `0xFFFFFFFF`. Refusée **avant** allocation : moins de 64 Kio alloués, trace d'appels de l'exception comprise, pour un texte « de 4 Gio ». Les 256 types de message, 16 encodages hostiles, 225 tailles d'écran (dont 65 535 × 65 535).
- **Flux hostiles** : chaque octet possible à chaque position de la bannière et de l'en-tête `ServerInit` (9 216 cas), flux aléatoires ou mutés (bits, valeurs limites, octets retirés ou insérés, troncature) pour chaque étape (handshake, sécurité 3.3 et 3.8 avec authentification VNC, messages), un flux valide **coupé à chaque position possible**, et la propriété « aucun pixel hors du rectangle annoncé n'est jamais modifié » sur 6 000 tuiles hostiles. À chaque fois : **aucune exception non typée**, la lecture **termine**, l'allocation reste bornée.
- **Mutations** : 7 défauts injectés dans le code (contrôle de bornes décalé d'un pixel, longueur du nom, du texte ou de la raison non bornée, longueur U32 lue comme `Int` signé, sous-rectangle Hextile non contenu dans sa tuile, destination CopyRect non validée avant lecture) : **les 7 sont détectés**.
- **Résultat de la revue** : aucun défaut trouvé dans le code de production. Les bornes étaient déjà posées ; l'apport est la preuve systématique.

**Limites connues (non couvertes, et pourquoi c'est acceptable)** :
- **Temps par message non borné** : un serveur qui envoie un octet toutes les `timeout − 1` secondes garde le client dans une lecture (le délai de lecture repart à chaque octet). Il ne peut nuire qu'à sa propre session, que l'utilisateur ferme ; non borné faute de besoin mesuré.
- **Débit et quantité** : jusqu'à 65 535 rectangles par mise à jour, chacun de la taille de l'écran, sont admis (ils sont bornés par le temps et la mémoire déjà allouée, pas par une limite de volume).
- **Écran plus grand que 1 920 × 1 200** : refusé (`InvalidFramebufferSize`), c'est le budget mémoire de la tablette, pas une erreur du serveur. Un serveur de 2 560 × 1 440 ne peut donc pas être affiché sans le réduire côté PC.
- **Texte du presse-papiers de plus de 1 Mio** : la session est coupée (`CutTextTooLong`) au lieu de l'ignorer.
- **Le format de pixels du serveur est validé strictement puis ignoré** (le client impose le sien) : un serveur au format exotique mais valide est accepté, un format incohérent est refusé même si on ne s'en servirait pas.
- **Pas de test sur un vrai serveur hostile** ni de fuzzing en continu : les flux sont produits par les tests, sur la JVM ; le comportement sur l'appareil n'est pas rejoué.

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
