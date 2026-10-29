# Plan — Sprint 4 smartphone : guide des programmes (EPG)

Objectif : afficher **ce qui passe maintenant et ensuite** sur chaque chaîne, consulter la **grille d'une chaîne** sur plusieurs jours et **chercher un programme**, à partir des guides XMLTV des playlists M3U et de l'EPG des fournisseurs Xtream. Les chaînes gardent déjà leur identifiant de guide (`tvg-id` M3U, `epg_channel_id` Xtream, champ `Channel.tvgId`) : ce sprint ajoute les programmes.

Mêmes règles qu'aux sprints précédents : smartphone uniquement (API 26+), aucun identifiant, flux, guide ou contenu livré avec l'application, aucun backend, aucune télémétrie, aucune adresse ni identifiant journalisé.

## Prérequis

- La PR #8 (Sprint 3) est fusionnée dans `main` (29 octobre 2026). Le Sprint 4 part de `main`, sur la branche `feature/sprint4-epg`.
- La PR #9 répare le fichier de workflow CI, illisible depuis le lot 0.2 du Sprint 3 : aucun run GitHub n'a vérifié les lots 1 à 4. Le premier run vert sur `main` est attendu avant la PR de ce sprint.
- Pour la validation sur appareil : le compte Xtream de test du Sprint 3 et une playlist M3U dont l'en-tête porte `url-tvg` (ou une adresse XMLTV saisie à la main).

## Décisions de cadrage

- **Deux voies d'alimentation.** Playlists M3U : un **fichier XMLTV** (adresse `url-tvg`/`x-tvg-url` de l'en-tête `#EXTM3U`, ou saisie par l'utilisateur dans la fiche source), éventuellement compressé gzip, importé en flux. Fournisseurs Xtream : `get_short_epg` **à la demande** pour la chaîne sélectionnée ou ouverte (quelques programmes, mis en cache), et `xmltv.php` du fournisseur **limité aux chaînes favorites** à chaque actualisation. Un portail de 28 000 chaînes publie plusieurs millions de programmes par semaine : les charger tous est exclu sur un téléphone.
- **Fenêtre conservée** : de 6 h avant l'import à 3 jours après ; les programmes terminés depuis plus de 12 h sont purgés à chaque actualisation et au démarrage. Un guide XMLTV est lu en flux (SAX) et inséré par lots : rien d'autre que le lot courant n'est en mémoire.
- **Rattachement** d'un programme à une chaîne par `tvgId` ; à défaut, pour une playlist M3U, par le nom affiché normalisé (`searchKey`) des `<channel>` du guide. La clé retenue est écrite sur la chaîne à l'import (`epgKey`) pour que les requêtes restent une jointure simple.
- **Heures** : XMLTV donne `yyyyMMddHHmmss ±hhmm` ; sans décalage, UTC. `get_short_epg` donne `start_timestamp`/`stop_timestamp` (époque) ; à défaut, `start`/`end` lus dans le fuseau du téléphone. Tout est stocké en millisecondes d'époque et affiché dans le fuseau local.
- **Pas de grille multi-chaînes horizontale** : sur téléphone, le guide est vertical, une chaîne à la fois, avec des puces de jour. La rangée « En ce moment » de l'Accueil et la ligne « maintenant / ensuite » des cartes TV couvrent le besoin de survol.
- **Recherche** : le groupe « Programmes · n » cherche dans les titres de la fenêtre conservée ; un résultat ouvre le guide de la chaîne à l'heure du programme.

## Lot 1 — Parseurs et modèle

- **Domaine** (`Domain.kt`) : `Programme(channelKey, startMs, stopMs, title, description?, category?, episode?)` avec `progress(nowMs)` et `isLive(nowMs)` ; `EpgChannel(id, names, iconUrl)` ; `ParsedPlaylist.epgUrl` (en-tête `#EXTM3U url-tvg=`/`x-tvg-url=`, première adresse si plusieurs).
- **Parseur XMLTV** (`Epg.kt`, nouveau) : SAX de `javax.xml.parsers` (réel sur l'appareil et sur la JVM des tests, contrairement à `XmlPullParser` qui n'est qu'un stub), détection gzip par les deux premiers octets, filtre de fenêtre et de chaînes passés par l'appelant, remise des programmes **par lots** à un consommateur ; `<title lang="fr">` préféré, entités XML gérées par SAX, `<episode-num system="xmltv_ns">` rendu « S1 E2 », programmes sans `stop` fermés par le début du suivant.
- **Parseur `get_short_epg`** (`XtreamParser.shortEpg`) : `epg_listings` avec titres et descriptions en base64, horodatages, tolérance aux champs manquants.
- **Client** : `XtreamAccount.xmltvUrl()` et `XtreamClient.shortEpg(account, channelKey, streamId, limit)` ; `Http` inchangé.
- **Tests** : XMLTV avec décalages horaires, entités, titres multilingues, programmes sans fin, gzip, document tronqué ou HTML ; 100 000 programmes filtrés en flux ; `get_short_epg` réel anonymisé et dégradé ; en-tête M3U avec et sans `url-tvg`.

### Lot 1 — réalisé (29 octobre 2026)

Livré dans `Domain.kt`, `Epg.kt` (nouveau), `Xtream.kt`, `M3uParser.kt`, les fixtures partagées (`EpgFixtures.kt`, `XtreamFixtures.SHORT_EPG`) et le faux client, avec 13 tests unitaires (99 au total, lint toujours à 28 avertissements). Décisions prises en cours de route :

- **SAX plutôt qu'un lecteur XML maison** : `javax.xml.parsers` est réel sur l'appareil (Expat) et sur la JVM des tests (Xerces), contrairement à `XmlPullParser` ; le gestionnaire ne garde que le programme courant, la liste des `<channel>` et le lot en cours. Le DTD déclaré par les générateurs XMLTV n'est jamais téléchargé (`resolveEntity` vide, chargement externe désactivé quand le parseur le permet). Un document dont la racine n'est pas `<tv>` échoue avec « Ce fichier n'est pas un guide XMLTV. », un document coupé avec « Guide XMLTV incomplet ou illisible. » : l'import garde alors les programmes précédents (lot 2).
- **Fenêtre et filtre de chaînes dans le parseur** : 100 000 programmes de 200 chaînes filtrés sur 50 chaînes favorites et 72 h en 0,6 s sur le Mac, 3 600 gardés, aucun autre programme instancié.
- **Heures** : `parseXmltvTime` accepte les champs tronqués (`yyyyMMddHHmm`), `Z`, `±hh:mm` et traite l'absence de décalage ou un décalage aberrant comme UTC. Les programmes sans `stop` se ferment au début du suivant sur la même chaîne et sont abandonnés sinon.
- **`get_short_epg`** : titres et descriptions décodés seulement si le base64 donne de l'UTF-8 imprimable (un titre en clair comme « News » est aussi du base64 valide) ; `start_timestamp`/`stop_timestamp` d'abord, `start`/`end` dans un fuseau fourni ensuite ; `epg_listings` en tableau, en objet indexé ou réponse `[]` acceptés.
- **En-tête M3U** : `url-tvg`, `x-tvg-url` ou `tvg-url` du premier `#EXTM3U`, première adresse `http(s)` d'une liste séparée par des virgules, rendue dans `ParsedPlaylist.epgUrl` ; la ligne n'est jamais prise pour une chaîne.

## Lot 2 — Persistance et import

- **Room v4 et migration 3 → 4** : table `programmes` (clé `(sourceId, channelKey, startMs)`, `stopMs`, `title`, `description`, `category`, `episode`, `searchTitle` ; index `(sourceId, channelKey, startMs)`, `(stopMs)`, `(startMs)`) ; colonnes `epgUrl`, `epgRefreshedAt`, `epgError`, `programmeCount` sur `sources` ; colonne `epgKey` sur `channels` (index `(sourceId, epgKey)`). Schéma exporté `4.json`, étape 3 → 4 dans `MigrationTest`.
- **Import** : avec l'actualisation de la source puis séparément (« Actualiser le guide ») ; insertion par lots de 500 dans des transactions courtes ; remplacement par source ; purge ; échec du guide consigné dans `epgError` sans toucher aux chaînes.
- **Dépôt** (`ChannelRepository`) : `setEpgUrl(sourceId, url)`, `refreshEpg(sourceId)`, `nowAndNext(channelIds, nowMs): Flow<Map<String, NowNext>>`, `programmes(channelId, dayStartMs): Flow<List<Programme>>`, `searchProgrammes(text): Flow<PagingData<ProgrammeHit>>`, `ensureShortEpg(channelId)` (Xtream, cache 30 min). `MemoryChannelRepository` suit, pour les tests ViewModel.
- **Version** 0.4.0 (`versionCode` 4).

### Lot 2 — réalisé (29 octobre 2026)

Livré dans `Domain.kt`, `Persistence.kt` (Room v4, migration 3 → 4, schéma `4.json`), `Sources.kt`, `Epg.kt`, les fixtures partagées (`GuideFixtures`, `GuideLoader`, `TestClock`, `EpgScenarios`) et les tests, version 0.4.0 (`versionCode` 4). Décisions prises en cours de route :

- **Un seul jeu de scénarios pour les deux dépôts** : `EpgScenarios` (sources partagées) décrit le comportement attendu et tourne sur `MemoryChannelRepository` (5 tests JVM, 104 au total) et sur `RoomChannelRepository` (5 tests instrumentés, 32 au total dont la mesure de performance ignorée), plus l'étape 3 → 4 de `MigrationTest`. Lint reste à 28 avertissements. L'horloge est injectée (`clock`) pour figer les fenêtres au 29 octobre 2026, 20 h 30.
- **Import en deux temps dans Room** : les programmes sont insérés par lots sous `-sourceId` pendant la lecture en flux (insertion bloquante depuis le collecteur SAX, une transaction courte par lot), puis échangés avec les anciens en une transaction ; un téléchargement interrompu ou un fichier illisible laisse le guide précédent et inscrit `epgError` sur la source. Aucun programme n'est gardé en mémoire au-delà du lot courant.
- **Clé de guide sur la chaîne** (`channels.epgKey`, index `(sourceId, epgKey)`) : `tvg-id` quand le guide déclare la chaîne, sinon la chaîne du guide portant le même nom affiché (comparaison `searchKey`), sinon le `tvg-id` tel quel (un guide peut omettre ses `<channel>`). Recalculée à chaque import du guide, donc après chaque actualisation de la playlist. Les chaînes Xtream sans `epg_channel_id` reçoivent `stream:{id}` au premier `get_short_epg`.
- **Guide d'un fournisseur** : sans favori, rien n'est téléchargé (`programmeCount` 0, sans erreur) ; avec des favoris, `xmltv.php` est lu en flux avec le filtre de leurs clés. `ensureShortEpg` n'interroge le fournisseur que si aucun programme en cours n'est connu pour la chaîne ou si le dernier appel date de plus de 30 min ; le remplacement du guide vide ce cache.
- **« Maintenant / ensuite »** en SQL : programmes en cours joints aux chaînes, et pour chacun le premier programme commençant à sa fin ou après (sous-requête `MIN(startMs)` sur la clé primaire). Une chaîne sans programme en cours n'a pas de « ensuite ». La recherche porte sur `searchTitle` des programmes non terminés, 200 résultats au plus, triés par heure.
- **Fenêtre** : import limité à `[maintenant − 6 h, maintenant + 3 jours[`, purge des programmes finis depuis plus de 12 h à chaque import et sur demande (`purgeProgrammes`, appelé au démarrage par le lot 3).

## Lot 3 — Interface

- **Cartes TV** : ligne « maintenant » (titre et barre de progression) quand un programme est connu ; rien sinon, pas d'espace réservé.
- **Lecteur** : « En ce moment · Ensuite » sous le titre de la chaîne, en aperçu et en plein écran ; la sélection d'une chaîne Xtream déclenche `ensureShortEpg`.
- **Guide d'une chaîne** : feuille ouverte depuis la carte (bouton « Guide ») ou le lecteur ; puces de jour (Aujourd'hui, Demain, puis dates) ; liste des programmes avec heure, titre, description repliée, programme en cours surligné et défilement initial dessus ; « Lire » depuis la feuille.
- **Accueil** : rangée « En ce moment sur vos favoris » (chaînes favorites avec un programme en cours).
- **Recherche** : groupe « Programmes · n » (chaîne, heure, titre) ; toucher ouvre le guide de la chaîne au bon jour.
- **Sources** : fiche source M3U avec champ « Guide des programmes (XMLTV) » prérempli par `url-tvg`, bouton « Actualiser le guide », état (date, nombre de programmes, erreur) ; fiche Xtream avec l'état du guide des favoris.
- **ViewModel** : `LiveState.nowNext`, `guideSheet`, `homeNow`, `searchProgrammeCount` ; horloge partagée rafraîchie chaque minute pour les barres de progression.

### Lot 3 — réalisé (29 octobre 2026)

Livré dans `GuideScreens.kt` (nouveau), `LiveViewModel.kt`, `MainActivity.kt`, `Preview.kt` et `SourcesScreen.kt`, vérifié à l'écran sur l'émulateur avec une playlist servie par le Mac (en-tête `url-tvg`, guide de 240 programmes datés du jour) : cartes TV et Accueil avec « maintenant / ensuite », aperçu et plein écran avec le programme en cours, feuille guide (en-tête, « Lire », puces Aujourd'hui / Demain / dates, programme en cours surligné, descriptions dépliables), rangée « En ce moment sur vos favoris », groupe « Programmes · n » dont un résultat ouvre le guide de la chaîne au bon jour, fiche source avec champ XMLTV prérempli, état du guide et boutons « Enregistrer / Retirer / Actualiser le guide ». Décisions prises en cours de route :

- **L'horloge appartient à l'écran** : `LiveState.nowMs` ne bouge que par `tick()`, appelé par un `LaunchedEffect` à chaque minute pleine, et à l'ouverture d'un guide. Une boucle `delay` dans le ViewModel aurait fait tourner `advanceUntilIdle` sans fin dans les tests. Les flux « maintenant / ensuite », recherche de programmes et guide du jour dérivent de `nowMs` par `flatMapLatest`.
- **Chaînes favorites lues une fois par changement de favoris** (`repository.channel` sur chaque identifiant, les films et séries rendant `null`) pour construire la rangée de l'Accueil sans parcourir la liste paginée.
- **Feuille guide dans la `LazyColumn` partagée**, comme les fiches film et série : le défilement initial vise le programme en cours ou le résultat de recherche (`focusMs`) après chargement ; quand la chaîne est celle en lecture, l'aperçu reste affiché dans la feuille, sinon un bouton « Lire » ouvre l'onglet TV sur la chaîne.
- **`ensureShortEpg` à la sélection d'une chaîne et à l'ouverture de son guide** : une chaîne Xtream non favorite obtient ses prochains programmes au moment où ils servent, jamais en parcourant la liste.
- **Champ XMLTV** : `rememberSaveable` clé par identifiant et adresse enregistrée, après qu'une carte réutilisée pour une nouvelle source de même identifiant a restauré un texte vide.
- Hors écran : la description d'un programme se replie à deux lignes et se déplie au toucher ; aucune image de programme (hors périmètre).

## Lot 4 — Tests, CI et documentation

- Tests unitaires ViewModel (guide, now/next, recherche de programmes, URL de guide) et instrumentés (Room : import, purge, jointure par `epgKey`, repli par nom, favoris Xtream ; Compose : carte avec programme, feuille guide, groupe Programmes ; migration 3 → 4).
- CI inchangée (workflow réparé par la PR #9), contrôle du schéma `4.json`.
- `README.md` et `VALIDATION.md` réécrits pour le Sprint 4, puis validation sur le Xiaomi 15 : import d'un XMLTV réel (durée, taille de la base, mémoire), `get_short_epg` du fournisseur de test, affichage dans TV, lecteur, guide, Accueil et Recherche, purge après 12 h.

### Lot 4 — réalisé (29 octobre 2026)

`GuideViewModelTest` (6 tests) et `GuidePhoneTest` (4 tests Compose) ajoutés ; CI inchangée (le contrôle du schéma couvre `4.json`) ; `README.md` et `VALIDATION.md` réécrits pour le Sprint 4, avec la liste des scénarios à jouer sur le Xiaomi 15. Totaux : 110 tests unitaires, 36 instrumentés, lint 28 avertissements.

## Bilan du sprint

| Lot | Commit | État |
| --- | --- | --- |
| 1 — Parseurs et modèle | `ebf1e2e` | fait |
| 2 — Room v4 et import | `6d890f8` | fait |
| 3 — Interface | `2563114` | fait |
| 4 — Tests et documentation | ce commit | fait |

Validation sur le Xiaomi 15 faite le 29 octobre 2026 : 10 scénarios sur 14 vérifiés par adb, 3 rapportés par l'utilisateur, 1 non observé (erreur de guide), purge à constater le lendemain ; un constat à traiter (programmes orphelins conservés). Reste : corriger les orphelins, puis PR vers `main` une fois la PR #9 (workflow CI) fusionnée.

## Ordre

1. Lot 1 (parseurs, testables seuls), puis Lot 2 (persistance), puis Lot 3 (interface).
2. Lot 4 en continu ; validation sur appareil à la fin.

## Hors périmètre

Rattrapage (catch-up / timeshift, même si `has_archive` est lu), rappels et enregistrements, grille horizontale multi-chaînes, images de programmes, sous-titres externes, profils, contrôle parental, Chromecast, Android TV et tablettes.
