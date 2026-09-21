# Budget de performance

## Contraintes

La GT-P5110 est un matériel ancien. Le projet optimise d'abord la stabilité, la latence et les allocations.

## Framebuffer

1280 × 800 × 4 octets ≈ 4,1 Mio pour ARGB_8888. Plusieurs copies plein écran peuvent rapidement augmenter la pression mémoire. Éviter les conversions globales à chaque update.

## Objectifs initiaux

- 10–20 FPS pour un bureau peu animé ;
- pas d'objectif vidéo 30/60 FPS ;
- < 80 Mio de mémoire applicative comme cible initiale à confirmer ;
- pas de croissance mémoire continue ;
- traitement incrémental des rectangles.

## Mesures sur la GT-P5110 (Android 4.2.2)

| Mesure | Résultat | Source |
|---|---|---|
| Tas Java maximum d'une application | **48 Mio** | SS-003 (écran de diagnostic) |
| `Framebuffer` 1280×800 (SS-020) | ~4 000 Kio de tas | sonde SS-020 |
| Deux buffers 1920×1200 (framebuffer + équivalent Bitmap) | ~17 Mio alloués sans `OutOfMemoryError` | sonde SS-020, VM limitée à 48 Mio |
| Croissance du tas après 4 000 mises à jour | 0 Kio | sonde SS-020 |
| `fillRect` plein écran 1280×800 | ~10,1 ms | sonde SS-020 |
| `writeRect` plein écran 1280×800 | ~14,6 ms | sonde SS-020 |
| `writeRect` 64×64 / `fillRect` 16×16 | ~42 µs / ~6 µs | sonde SS-020 |
| Décodage RAW plein écran 1280×800 (4 Mio), loopback local, **sans Wi-Fi** | ~100–105 ms (médiane de 5 essais ; min 97, max 140) | sonde SS-023/024 |
| Réception RAW plein écran par **Wi-Fi** (4 Mio) | ~1,4 s (≈ 2,9 Mio/s), 3 essais : 1 406 / 1 386 / 1 438 ms | sonde SS-023/024 |
| Rendu partiel, cadence libre : 1 image = 2 rectangles 64×64 + un `lockCanvas(dirty)`/`unlockCanvasAndPost` | **50 à 67 images/s** (408 en 8,1 s ; 459 en 6,9 s ; 600 en 10,9 s) | pilote de test SS-031 |
| Ramasse-miettes pendant l'animation, régime établi (~22 s) | **0 événement** | pilote de test SS-031 |
| Rejet d'un rectangle hors écran, d'un encodage, d'un type de message ou d'un `ServerCutText` invalides (loopback local) | 0–1 ms | sonde SS-023/024 |
| Écran complet 1280×800 d'un vrai bureau (TigerVNC, terminaux à l'écran), **RAW seul**, par Wi-Fi, demande → écran décodé | ~1 700 ms | sonde SS-025/026, 1 essai |
| Même écran, **Hextile**, par Wi-Fi | ~90 ms (90 et 92 ms sur 2 essais) | sonde SS-025/026 |

À interpréter avec prudence : ces valeurs viennent d'une sonde ponctuelle exécutée avec `app_process -Xmx48m` (VM du shell, pas le processus de l'application), une seule série de mesures. Elles servent de base de comparaison, pas de garantie ; elles seront refaites dans l'application avec l'instrumentation de SS-060.

Observations de SS-023/024 :

- **Le décodage plein écran plafonne à ~10 mises à jour/s hors réseau** (~100 ms par 4 Mio). La mesure inclut la copie locale par la socket, donc le coût de la seule conversion d'octets en ARGB n'est pas isolé : c'est le point de départ de SS-062, pas une conclusion. Les mises à jour partielles coûtent proportionnellement à leur surface.
- **Sur ce Wi-Fi, c'est le réseau qui limite, pas le CPU** : un écran complet en RAW met ~1,4 s à arriver, soit 14 fois le temps de décodage. C'est un argument pour Hextile (SS-026/063) et pour éviter les mises à jour plein écran ; à confirmer avec un vrai serveur.
- **Latence de première réception** : expliquée et corrigée, voir « Latence Wi-Fi de la tablette » ci-dessous (SS-064).

Observation de SS-025/026 :

- **Hextile change l'ordre de grandeur pour un bureau d'applications** : sur un vrai bureau (TigerVNC, fond uni et terminaux de texte), l'écran complet arrive en ~90 ms en Hextile contre ~1 700 ms en RAW, soit environ 19 fois plus vite, décodage inclus, avec un écran reconstruit identique au serveur. **Portée limitée** : un seul bureau de test, surtout uni ou en texte, quelques essais. Hextile est très favorable à ce type de contenu et le serait beaucoup moins pour de la vidéo ou des photos (tuiles brutes). C'est un premier indice en faveur du choix d'encodage, pas le benchmark de SS-063, qui devra comparer RAW et Hextile sur plusieurs types de contenu.
- **CopyRect n'a pas de coût de réseau** : un défilement de terminal envoie 4 octets par rectangle au lieu des pixels ; pendant le test, ~26 millions de pixels ont été reconstruits par copie interne (`copyRect`, sans allocation) sur ~270 mises à jour.

Observations de SS-031 :

- **Le rendu par zone suit le rythme des petites mises à jour** : ~55 images/s pour des rectangles de 64×64, sans allocation mesurable (aucun passage du ramasse-miettes en 22 s). La dispersion entre essais (50 à 67) n'a pas été expliquée.
- **Portée limitée** : c'est un pilote de test (un carré qui bouge), pas un vrai flux de décodage. Le coût d'un rendu **plein écran** (~4 Mio à copier dans le Bitmap puis à dessiner) n'est pas mesuré ici ; il l'est pour la partie framebuffer (~15 ms, voir plus haut) mais pas pour `setPixels` + `drawBitmap`. C'est le travail de SS-060/SS-062 avec une vraie session.
- Le critère « sans allocation massive par frame » de SS-031 est vérifié par l'absence de ramasse-miettes, pas par un compteur d'octets.

Conséquences :

- La cible « < 80 Mio » ne peut concerner que la mémoire totale du processus : le **tas Java plafonne à 48 Mio**. Le budget des buffers d'écran (`Framebuffer.MAX_PIXELS`) est dimensionné sur cette limite.
- Une mise à jour plein écran coûte ~15 ms rien que pour copier les pixels, avant conversion de format et transfert vers le Bitmap. Sur un bureau peu animé les mises à jour sont des petits rectangles (quelques dizaines de µs) : c'est ce qu'il faut préserver (SS-062, aucune copie plein écran inutile).

## Latence Wi-Fi de la tablette (SS-064)

**Symptôme** : après un moment sans échange, l'arrivée de données sur la tablette prenait de ~0,2 s à ~2 s alors que le serveur envoyait immédiatement (observé pendant SS-023/024).

**Cause établie par mesure** : quand la liaison est silencieuse, la radio Wi-Fi de la tablette s'endort ; un paquet qui arrive attend qu'elle se réveille. La latence dépend du **rythme du trafic**, pas d'un verrou système. Ping PC → tablette, GT-P5110 (Android 4.2.2), Wi-Fi 2,4 GHz :

| Trafic sur la liaison | RTT médian | RTT au p90 | Maximum |
|---|---|---|---|
| 1 ping/s (liaison quasi silencieuse) | 500 à 790 ms | 1,1 à 1,7 s | ~1,9 s |
| 5 pings/s | 3,8 à 4,9 ms | 5,2 à 7,3 ms | ≤ 10 ms (un pic isolé à 34 ms sur 4 séries) |

**Ce qui ne marche pas** : le `WifiLock` `WIFI_MODE_FULL_HIGH_PERF` (supposé désactiver l'économie d'énergie) **n'a aucun effet mesurable** sur cet appareil. Alternance verrou tenu/relâché ×2 (le système confirmait 1 verrou tenu / 0) : à 1 ping/s (PC → tablette) les médianes valaient 649 et 785 ms verrou tenu contre 754 et 787 ms verrou relâché, les p90 1 568 à 1 729 ms dans les deux cas, et la part de pings > 50 ms 76 à 96 % contre 72 % ; à 5 pings/s, 4,7 et 4,8 ms contre 4,9 et 3,8 ms. Dans le sens tablette → PC, mêmes constats (médianes 62 à 77 ms, p90 ≈ 140 ms) et des pics de ~1 s dans les deux conditions. Le code a été écrit puis **retiré** (avec sa permission `WAKE_LOCK`) plutôt que de laisser un mécanisme qui prétend régler un problème qu'il ne règle pas. À ne pas retenter sans nouvelle donnée.

**Ce qui marche** : émettre un petit paquet (10 octets) depuis la tablette à intervalle régulier suffit à garder la radio éveillée. Ping PC → tablette à cadence décalée (1,13 s, pour éviter tout calage de phase), 40 mesures par ligne :

| Paquet émis par la tablette | Médiane | p90 | Max | Pings > 50 ms |
|---|---|---|---|---|
| aucun | 707 ms | 1 525 ms | 1 747 ms | 85 % |
| toutes les 400 ms | 4,8 ms | 98 ms | 372 ms | 18 % |
| toutes les 300 ms | 4,6 ms | 38 ms | 217 ms | 8 % |
| toutes les 250 ms | 4,6 ms | 88 ms | 276 ms | 12 % |
| toutes les 200 ms | 4,7 ms | 20 ms | 118 ms | 8 % |
| **toutes les 100 ms** | **3,5 ms** | **5,8 ms** | 103 ms | **2 %** |
| aucun (contrôle final) | 697 ms | 1 643 ms | 1 939 ms | 82 % |

Le contrôle final sans trafic revient au niveau initial : l'effet vient du battement. Des mesures à 1 et 0,5 s d'intervalle, calées en phase sur le ping à 1 Hz, sont écartées (RTT quasi constants, non interprétables).

**Correctif** : `KeepAlive` envoie toutes les **100 ms** un `FramebufferUpdateRequest` incrémental d'**un seul pixel** en (0, 0) sur la connexion RFB (`ClientMessages.keepAliveRequest`). C'est une requête légale à tout moment ; un serveur n'y répond que si ce pixel change. Coût : ~10 petits paquets/s (quelques kbit/s) et une radio qui reste éveillée : acceptable pour un moniteur branché, à ne faire tourner que pendant une session. Au-delà de 400 ms l'effet disparaît.

**Validation dans le vrai protocole** : client RFB réel (code de production) sur la GT-P5110, serveur TigerVNC réel, 18 changements **spontanés** côté PC par essai, alternance sans/avec battement ×2. Retard de la mise à jour reçue, au-dessus du meilleur cas de chaque essai :

| | Médiane | p90 | Max | > 100 ms |
|---|---|---|---|---|
| Sans battement (36 mesures) | 222 ms | 652 ms | 724 ms | **75 %** |
| Avec battement (36 mesures) | **3 ms** | 4 ms | 4 ms | **0 %** |

**Limites de ces mesures** :
- le retard est **relatif** au meilleur cas de chaque essai (les horloges du PC et de la tablette ne sont pas synchronisées) : il mesure la dispersion due à la radio, pas la latence absolue ;
- un premier essai de ce protocole a été **invalidé** : il déclenchait le changement par `tail -f`, qui interroge le fichier une fois par seconde dans un conteneur (sans `inotify`) et ajoutait lui-même 0 à 1 s de retard aléatoire. Le déclencheur a été remplacé par un tube nommé bloquant. Les résultats ci-dessus sont ceux du protocole corrigé ;
- **le PC est lui aussi en Wi-Fi avec l'économie d'énergie activée** (`iw dev wlan0 get power_save` : `on`), en 5,2 GHz à −69 dBm, alors que la tablette est en 2,4 GHz. Les pings n'isolent donc pas parfaitement une seule radio. Pour de meilleurs résultats côté PC, le désactiver ou utiliser Ethernet (à documenter dans les guides Ubuntu/Windows, SS-091/092) ;
- un seul point d'accès, une seule tablette, un seul réseau, quelques minutes de mesure. Un pic isolé à ~1 s vu dans quelques séries de ping n'a pas été reproduit et n'est pas expliqué (scan Wi-Fi en arrière-plan possible).

## Rendu mis à l'échelle (SS-033)

Écran distant 1920×1080 ajusté dans la surface 1280×752 de la GT-P5110 (échelle 0,667, filtrage bilinéaire, `Matrix` découpée à la zone modifiée), mesuré par une sonde temporaire (`System.nanoTime` autour du rendu, moyenne sur 40 rendus) puis retirée :

| Cas | Zone redessinée | Temps par rendu |
|---|---|---|
| horloge (petites mises à jour) | ~30 000 px | ~22 à 26 ms (max ~50 ms) |
| terminal qui défile en continu | ~400 000 px | 53 à 58 ms (max ~78 ms) |
| rendu complet | 1280×752 (962 560 px) | ~200 ms |

Un seul appareil, un serveur TigerVNC dans un conteneur, quelques dizaines de secondes par cas ; ce sont des ordres de grandeur, pas un benchmark (SS-060, SS-062, SS-063). Aucune optimisation n'a été faite : la première à essayer serait un filtrage plus léger ou un rendu partiel plus fin, à justifier par une mesure.

## Copies du framebuffer (SS-060, SS-062)

Mesuré **avec l'instrumentation de SS-060** (bandeau + journal `SecondScreenPerf`, moyennes par seconde après 3 s de démarrage, ~17 s par scénario) sur la GT-P5110, TigerVNC 1280×800 dans un conteneur, RAW, surface 1280×752 (barre système visible) : **rendu 1:1 rogné des 48 dernières lignes**, pas le rendu ajusté (voir plus bas). Mesures « avant » : même code, boîte englobante seule.

| Scénario | | Mises à jour/s | Copie par rendu | Dessin par rendu | Pixels copiés par rendu | CPU (1 cœur) |
|---|---|---|---|---|---|---|
| **2 petites zones aux coins opposés** | avant | | 22,4 ms | 18,3 ms | ~644 000 | 71 % |
| | **après** | 20,3 | **0,2 ms** | 23,2 ms | **~0 (quelques milliers)** | 42 % (1) |
| Terminal qui défile (grande zone) | avant | | 16,0 ms | | ~652 000 | 84 % |
| | après | 4,3 | 17,5 ms | 19,2 ms | ~676 000 | 70 % |
| Horloge (une petite zone) | après | 1,2 | 0,2 ms | 19,7 ms | ~0 | 2 % |

- **Le gain est sur la copie** : 22,4 → 0,2 ms par rendu pour des zones éparses. Pour une grande zone qui change vraiment (défilement), il n'y a **ni gain ni perte** (les 650 000 pixels sont réellement modifiés), comme attendu.
- **(1) Le CPU est bruité** : une série de six exécutions de 30 s, **toutes dans la même configuration** (mesures désactivées, sans que je l'aie vu à l'époque : voir « Coût des mesures » plus bas), a donné 31 à 33 % ou 48 à 51 % du cœur, sans que j'aie identifié la cause de ces deux régimes. La baisse de CPU de 71 % à 42 % (une exécution de chaque) est donc **indicative**, pas démontrée ; la baisse du temps de copie, elle, se lit directement sur les chronomètres.
- **Le dessin n'a pas baissé** (18,3 → 23,2 ms sur le scénario éparse) : la boîte englobante est toujours entièrement repeinte, et cette valeur varie d'une exécution à l'autre. Le coût fixe du dessin (~18 à 23 ms) est désormais le plafond : ~50 rendus/s au mieux. Le réduire demanderait de ne plus utiliser `lockCanvas` sur une zone couvrant les deux rectangles (par exemple un rendu par rectangle), non essayé faute de mesure qui le justifie.
- **Copies plein écran : 0** dans tous les scénarios (le défilement copie ~66 % du framebuffer, sous le seuil de 90 %).
- **Coût des mesures elles-mêmes** : voir la section « Coût des mesures » ci-dessous (mesuré dans un second temps, la première tentative ayant été invalide).
- Un seul appareil, un seul serveur, une seule configuration : ce sont des ordres de grandeur.

**Exactitude de l'image après copie partielle** (les optimisations ne doivent pas changer un pixel) : trois fenêtres éloignées mises à jour en continu, puis figées.
- **Rendu 1:1** (le défaut) : capture de la tablette comparée au **framebuffer serveur lu par un client RFB indépendant** : **0 pixel différent** (zone visible, hors la barre système qui cache les 48 dernières lignes). Mutation (ne copier que le premier rectangle) : **5 983 pixels faux**.
- **Rendu ajusté** (option « Échelle », échelle 0,94 : le xterm de 136 px en fait 128) : image après mises à jour partielles comparée à l'image du rendu complet obtenue en se reconnectant sur le même contenu figé : **0 pixel différent**. Même mutation : **3 264 pixels faux**.
- **Correction** : un premier contrôle « rendu ajusté » avait été fait **sans activer l'option** (le réglage était écrit dans un format que l'application ignore) : il testait en réalité le 1:1. Il a été refait avec l'option réellement active.

## Coût des mesures (SS-060)

Mesuré sur la GT-P5110 : CPU **du processus entier** (`utime + stime` de `/proc/<pid>/stat` sur 30 s) contre une charge serveur fixe (deux petites zones qui changent 20 fois par seconde, rendu 1:1), 3 alternances mesures activées / désactivées, session rouverte à chaque fois.

| | Série 1 | Série 2 | Série 3 | Moyenne |
|---|---|---|---|---|
| Mesures **activées** (bandeau, journal, comptage d'allocations Dalvik) | 42,5 % | 48,3 % | 47,4 % | **46,0 %** |
| Mesures **désactivées** | 44,8 % | 40,1 % | 40,7 % | **41,9 %** |

- **Environ +4 points de CPU d'un cœur (~10 % de plus)** avec les mesures activées. **Les plages se recouvrent** (une série « désactivée » à 44,8 % dépasse une série « activée » à 42,5 %) et il n'y a que 3 séries par cas : c'est une estimation, pas une mesure précise.
- Ce coût comprend : deux `nanoTime` par rendu, quelques compteurs atomiques, le comptage d'allocations de la VM, et surtout **l'affichage** (texte du bandeau et ligne de journal, une fois par seconde, ~380 objets/s sur le thread UI, voir plus bas).
- Désactivées, l'application n'exécute qu'une lecture de booléen par point de mesure ; aucune ligne de journal (vérifié : 0 ligne en 6 s).
- **Erreur de la première tentative** : les mesures « activées » et « désactivées » y avaient été demandées par un réglage écrit dans un format ignoré par l'application (booléen XML au lieu de chaîne) : les six séries étaient en réalité **toutes désactivées**. Leur dispersion (31 à 51 %) est donc la variabilité entre exécutions **d'une même configuration** sur une autre charge ; elle rappelle qu'une seule exécution de chaque ne prouve rien.

## Allocations (SS-061)

Session de référence : `scripts/reference_session.py` (voir DEVELOPMENT.md). Charge serveur en boucle de cinq phases de 60 s (horloge, deux zones éparses à 20 mises à jour/s, terminal qui défile, fenêtres qui s'ouvrent et se ferment, repos), un toucher, un glissement, un texte ou une touche effacer toutes les 15 s, mesures activées, rendu 1:1, GT-P5110 Android 4.2.2, TigerVNC 1280×800 dans un conteneur, RAW/Hextile selon le serveur.

### Session de 30 min

1 742 lignes de mesure (une par seconde), **0 trou, 0 anomalie**, un seul processus (l'application n'a ni redémarré ni planté), 119 entrées envoyées.

| Phase | mises à jour/s | objets alloués/s par le **thread de session** | **par mise à jour** | octets/s | objets/s, **processus entier** | dont thread UI | CPU % |
|---|---|---|---|---|---|---|---|
| horloge | 1,1 | 0 | 0,2 | 12 | 416 | 384 | 2 |
| deux zones éparses | 16,6 | 16 | 1,0 | 205 | 433 | 384 | 29 |
| terminal qui défile | 10,7 | 143 | 13,4 | 1 727 | 564 | 388 | 75 |
| fenêtres ouvertes/fermées | 2,4 | 28 | 11,8 | 343 | 444 | 385 | 19 |
| repos | 0,4 | 0 | 0,3 | 4 | 415 | 384 | 1 |
| **toutes** | 6,3 | 38 | 6,1 | 464 | 455 | 385 | 26 |

- **Le chemin chaud (thread de session : lecture, décodage, rendu) alloue très peu** : de 0 à ~1,7 Ko/s, y compris au pire (terminal qui défile). Au repos, il n'alloue **rien** (4 octets/s, l'objet du message de battement).
- **L'essentiel des allocations est celui des mesures elles-mêmes** : ~385 objets/s et ~21 Ko/s sur le thread UI, à toute charge, sont le texte du bandeau et la ligne de journal, une fois par seconde. Avec les mesures désactivées, l'application alloue donc **moins que ce tableau** (voir la borne ci-dessous).
- **Origine probable des allocations du thread de session** (lecture du code, **non vérifiée par une mesure dédiée**) : un objet `FramebufferUpdated` par mise à jour, et un itérateur par rectangle (`decoderList.firstOrNull { … }` sur une `List`). Cohérent avec ~1 objet par mise à jour dans « deux zones éparses » et ~12 à 13 pour les mises à jour à nombreux rectangles. Quelques objets de 16 à 24 octets par mise à jour : **aucune correction n'est justifiée par la mesure** (AGENTS.md).
- **Ramasse-miettes** (journal Dalvik, 22 pour l'application en 30 min = **0,7/min**) : `GC_CONCURRENT`, ~1,9 Mo libérés chacun, 65 ms en moyenne (max 103 ms) dont l'essentiel en arrière-plan. Environ **24 Ko/s** libérés, c'est-à-dire l'allocation totale **avec** les mesures. La fréquence est la même dans toutes les phases, ce qui confirme que le bruit de fond dominant est celui du bandeau. `Debug.getGlobalGcInvocationCount()` rend **toujours 0** sur cet appareil (0 sur 1 774 s pour 22 collectes) : il n'est pas utilisé.
- **Aucune fuite visible en 30 min** : le tas Java utilisé **après** ramasse-miettes est resté à 13 186 → 13 187 Ko (pente −2 Ko/h) ; PSS total 44,6 → 40,9 Mo (en baisse) ; threads 14 à 15 ; descripteurs de fichiers 56 constant ; `Views` 110, `Activities` 3, `ViewRootImpl` 2 à 3, contextes 5, tous constants. Aucune alerte de l'outil d'analyse.

### Session de 2 h

*Voir ci-dessous une fois terminée.*

### Limites

- Un seul appareil, un seul serveur (conteneur sur le même réseau Wi-Fi), un seul profil de charge synthétique : ce n'est pas une session d'utilisation réelle (pas de vidéo, pas de saisie longue, pas de rotation, pas de mise en veille).
- Les entrées viennent de `adb shell input` (un toucher ou un glissement à un doigt, du texte, une touche) : pas de geste à deux ou trois doigts, pas de mode touchpad.
- Les chiffres d'allocation sont ceux de Dalvik (`Debug.getThreadAllocCount`, etc.) : objets et octets demandés, pas la mémoire résidente.
- Le bandeau et le comptage des allocations font partie de ce qui est mesuré ; l'allocation « de production » (mesures désactivées) n'a pas été mesurée directement.

## Mesures avant optimisation

Toujours mesurer RAW avant d'implémenter un encodage plus complexe. Hextile économise potentiellement du réseau mais consomme du CPU. Le meilleur compromis doit être établi sur la tablette réelle.

## Anti-patterns

- créer un nouveau Bitmap pour chaque update ;
- `ByteArray` plein écran alloué en boucle ;
- convertir chaque pixel via objets Kotlin ;
- collections temporaires dans les boucles pixels ;
- logging par rectangle/pixel en release ;
- redessiner tout l'écran lorsqu'un petit rectangle change sans nécessité.
