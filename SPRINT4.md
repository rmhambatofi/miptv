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

## Lot 2 — Persistance et import

- **Room v4 et migration 3 → 4** : table `programmes` (clé `(sourceId, channelKey, startMs)`, `stopMs`, `title`, `description`, `category`, `episode`, `searchTitle` ; index `(sourceId, channelKey, startMs)`, `(stopMs)`, `(startMs)`) ; colonnes `epgUrl`, `epgRefreshedAt`, `epgError`, `programmeCount` sur `sources` ; colonne `epgKey` sur `channels` (index `(sourceId, epgKey)`). Schéma exporté `4.json`, étape 3 → 4 dans `MigrationTest`.
- **Import** : avec l'actualisation de la source puis séparément (« Actualiser le guide ») ; insertion par lots de 500 dans des transactions courtes ; remplacement par source ; purge ; échec du guide consigné dans `epgError` sans toucher aux chaînes.
- **Dépôt** (`ChannelRepository`) : `setEpgUrl(sourceId, url)`, `refreshEpg(sourceId)`, `nowAndNext(channelIds, nowMs): Flow<Map<String, NowNext>>`, `programmes(channelId, dayStartMs): Flow<List<Programme>>`, `searchProgrammes(text): Flow<PagingData<ProgrammeHit>>`, `ensureShortEpg(channelId)` (Xtream, cache 30 min). `MemoryChannelRepository` suit, pour les tests ViewModel.
- **Version** 0.4.0 (`versionCode` 4).

## Lot 3 — Interface

- **Cartes TV** : ligne « maintenant » (titre et barre de progression) quand un programme est connu ; rien sinon, pas d'espace réservé.
- **Lecteur** : « En ce moment · Ensuite » sous le titre de la chaîne, en aperçu et en plein écran ; la sélection d'une chaîne Xtream déclenche `ensureShortEpg`.
- **Guide d'une chaîne** : feuille ouverte depuis la carte (bouton « Guide ») ou le lecteur ; puces de jour (Aujourd'hui, Demain, puis dates) ; liste des programmes avec heure, titre, description repliée, programme en cours surligné et défilement initial dessus ; « Lire » depuis la feuille.
- **Accueil** : rangée « En ce moment sur vos favoris » (chaînes favorites avec un programme en cours).
- **Recherche** : groupe « Programmes · n » (chaîne, heure, titre) ; toucher ouvre le guide de la chaîne au bon jour.
- **Sources** : fiche source M3U avec champ « Guide des programmes (XMLTV) » prérempli par `url-tvg`, bouton « Actualiser le guide », état (date, nombre de programmes, erreur) ; fiche Xtream avec l'état du guide des favoris.
- **ViewModel** : `LiveState.nowNext`, `guideSheet`, `homeNow`, `searchProgrammeCount` ; horloge partagée rafraîchie chaque minute pour les barres de progression.

## Lot 4 — Tests, CI et documentation

- Tests unitaires ViewModel (guide, now/next, recherche de programmes, URL de guide) et instrumentés (Room : import, purge, jointure par `epgKey`, repli par nom, favoris Xtream ; Compose : carte avec programme, feuille guide, groupe Programmes ; migration 3 → 4).
- CI inchangée (workflow réparé par la PR #9), contrôle du schéma `4.json`.
- `README.md` et `VALIDATION.md` réécrits pour le Sprint 4, puis validation sur le Xiaomi 15 : import d'un XMLTV réel (durée, taille de la base, mémoire), `get_short_epg` du fournisseur de test, affichage dans TV, lecteur, guide, Accueil et Recherche, purge après 12 h.

## Ordre

1. Lot 1 (parseurs, testables seuls), puis Lot 2 (persistance), puis Lot 3 (interface).
2. Lot 4 en continu ; validation sur appareil à la fin.

## Hors périmètre

Rattrapage (catch-up / timeshift, même si `has_archive` est lu), rappels et enregistrements, grille horizontale multi-chaînes, images de programmes, sous-titres externes, profils, contrôle parental, Chromecast, Android TV et tablettes.
