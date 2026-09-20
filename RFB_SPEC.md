# Sous-ensemble RFB à implémenter

## Objectif

Implémenter le minimum RFB nécessaire à l'usage « deuxième écran », puis enrichir progressivement.

## Versions

Support initial : RFB 3.3 et 3.8 si possible. La version réellement utilisée est choisie après lecture de la bannière serveur.

### Négociation (SS-011)

Le serveur envoie 12 octets ASCII `RFB xxx.yyy\n` ; le client répond avec la plus haute version supportée ne dépassant pas celle du serveur :

| Version annoncée par le serveur | Réponse du client |
|---|---|
| ≥ 3.8 (dont 3.889 d'Apple, 4.x) | `RFB 003.008\n` |
| 3.3 à 3.7 | `RFB 003.003\n` (3.7 n'est pas implémenté) |
| < 3.3 | erreur `UnsupportedVersion`, connexion fermée |

Une bannière qui ne respecte pas strictement le format (préfixe, chiffres ASCII, séparateur, `\n`) produit `InvalidBanner` — cas typique : port qui n'est pas un serveur VNC. Le contenu reçu n'est jamais recopié dans les messages d'erreur.

### Négociation de sécurité (SS-012)

Le comportement diffère selon la version négociée (RFC 6143 §7.1.2) :

| | RFB 3.8 | RFB 3.3 |
|---|---|---|
| Types proposés | U8 `n` puis `n` U8 ; `n = 0` = refus + raison | un seul U32 imposé ; `0` = refus + raison |
| Choix du client | 1 octet (premier type de **sa** liste de préférence que le serveur propose) | aucun |
| `SecurityResult` (U32, 0 = OK, 1 = échec) | toujours, y compris pour `None` | sauf pour `None` |
| Raison en cas d'échec | oui (U32 longueur + texte) | non |

Types gérés : `None` (1) ; VNC Authentication (2) s'ajoute en implémentant `SecurityHandler` (SS-014), sans modifier la négociation. Les autres types (Tight, TLS, etc.) donnent `NoSupportedSecurityType` avec la liste proposée.

Le serveur est une entrée non fiable : le texte de raison est lu sur **256 octets au plus** quelle que soit la longueur annoncée (U32), puis assaini (ASCII imprimable, le reste devient `?`). Il est exposé dans `reason` des erreurs `ConnectionRejected` / `AuthenticationFailed`, jamais dans leur message. Un serveur qui coupe sans envoyer de raison donne une raison vide plutôt qu'une erreur réseau. Toute erreur ferme la socket.

`None` ne doit servir que sur un LAN de développement de confiance (voir SECURITY.md).

## Séquence

```text
TCP connect
→ ProtocolVersion
→ Security negotiation
→ SecurityResult
→ ClientInit
→ ServerInit
→ SetPixelFormat
→ SetEncodings
→ FramebufferUpdateRequest
↔ FramebufferUpdate
↔ PointerEvent / KeyEvent
```

## Messages serveur nécessaires

- FramebufferUpdate ;
- Bell : ignorer proprement ;
- ServerCutText : ignorer dans le MVP en consommant correctement le message.

Tout type inconnu doit produire une erreur protocolaire contrôlée plutôt qu'un désalignement silencieux du flux.

## Messages client nécessaires

- SetPixelFormat ;
- SetEncodings ;
- FramebufferUpdateRequest ;
- KeyEvent ;
- PointerEvent.

## Encodages

### RAW — P0

Premier encodage. Valider : coordonnées, largeur, hauteur, bytes-per-pixel et taille calculée avant lecture/allocation.

### CopyRect — P1

Copie locale d'une région déjà présente dans le framebuffer.

### Hextile — P1

Décodage par tuiles de 16×16. Ajouter des tests unitaires couvrant toutes les combinaisons de sous-encodage prises en charge.

### ZRLE/Tight — P3

Non nécessaires tant que les mesures ne montrent pas que RAW/Hextile limitent l'usage réel.

## Pixel format

Format client préféré initial : true color 32 bits. Les conversions doivent être isolées dans une classe/fonction testable. Attention à l'endianness et aux shifts/max RGB.

## Robustesse

Avant toute multiplication de dimensions reçues du réseau, vérifier les bornes pour éviter overflow et allocations abusives. Aucun rectangle ne peut dépasser le framebuffer négocié.
