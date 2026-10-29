# Validation — smartphone

## Sprint 3 — synthèse (29 octobre 2026)

Branche `feature/sprint3-xtream`, un commit par lot ; chaque commit compile et passe la commande CI locale `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`, puis `connectedDebugAndroidTest` sur l'émulateur Pixel 2 API 35 x86_64 (macOS Intel, JDK 21, SDK 35).

| Lot | Commit | Contenu | Vérification |
| --- | --- | --- | --- |
| 0.1 — Schéma exporté | `8a99470` | `exportSchema`, `app/schemas` versionné, `MigrationTest` sur une base v1 remplie | CI : le schéma généré doit être commité |
| 0.2 — Pagination | `7836642` | Pagination par clé depuis Room, favoris sans réémission du catalogue | Mesures sur 50 000 chaînes ci-dessous |
| 0.3 — Validation appareil | `635ee4b` | Scénarios du Sprint 2 sur le Xiaomi 15 | Section dédiée ci-dessous |
| 0.4 — Délai borné | `430bc42` | Erreur et « Réessayer » après 20 s de chargement | `PlayerTimeoutTest`, Xiaomi 15 |
| 1 — Client Xtream | `5c36b25` | Parseurs en flux, client HTTP, URL de lecture, erreurs en français | 33 tests unitaires, 30 000 films en 0,44 s |
| 2 — Identifiants et Room v3 | `6023f64` | Mot de passe chiffré (Keystore), tables VOD, caches, favoris, reprise, migration 2 → 3 | 9 unitaires, 7 instrumentés |
| 3 — Interface | `b381fd8` | Formulaire Xtream, Films, Séries, lecteur VOD unique, Accueil, Recherche groupée | 6 unitaires, 4 Compose |
| 4 — Tests et documentation | ce commit | Tests du lecteur VOD et des formats, README et VALIDATION réécrits | Totaux ci-dessous |

État final : **86 tests unitaires** et **27 tests instrumentés** réussis, lint **28 avertissements** (mises à jour de dépendances épinglées, cible API, écran tactile requis, un `InputStream` fermé par l'appelant), zéro erreur, schéma Room v3 inchangé depuis le lot 2. Aucun test ne dépend du réseau : réponses Xtream enregistrées et anonymisées, serveurs locaux sur `ServerSocket`, faux client, clé AES générée sur place.

### Validation sur appareil (Xiaomi 15, compte Xtream de test, 29 octobre 2026)

Les 17 scénarios ci-dessous ont été joués par l'utilisateur sur son compte Xtream réel (fournisseur de test, 28 551 chaînes, 161 342 films, 63 798 séries). Bilan :

- **14 scénarios vérifiés par adb** (base copiée via `run-as`, `logcat`, `dumpsys`) : migration 2 → 3 en moins d'une seconde, ajout du fournisseur en une minute environ, mot de passe chiffré `v1:` sans aucune occurrence en clair dans la base ni dans `logcat`, lecture TV, film et épisode avec positions en base, 2,4 % d'images saccadées sur les grilles, mémoire 320 Mo en régime établi, relance en 453 ms sans réimport, clé Keystore relue après redémarrage, suppression du fournisseur qui vide toutes les tables VOD et ses favoris et positions ;
- **3 scénarios rapportés par l'utilisateur seulement** : 13 (actualisation du fournisseur), 14 (arrière-plan pendant un film) et 15 (mode avion). Les trois dépendent d'un état transitoire que la coupure du débogage Wi-Fi (mode avion, redémarrage) puis la suppression du fournisseur ont effacé avant lecture ; leurs messages d'erreur sont couverts par les tests automatisés ;
- **un écart observé** : le scénario 11 n'avait d'abord posé que le favori film ; le favori série a été posé et vérifié en base ensuite.

Restent hors de portée sans second appareil ou sans compte dédié : la perte de clé après restauration (« Identifiants à ressaisir », couverte par `XtreamRepositoryTest`), un compte expiré ou saturé en connexions sur un vrai panel, et l'hétérogénéité des panels inconnus.

### Scénarios à jouer sur le Xiaomi 15

APK debug du commit `07bf612` déposé le 29 octobre 2026 dans Téléchargements sous `miptv-0.3.0-sprint3-07bf612.apk`. Le téléphone porte alors la version du lot 0.4 (installée à 13:27) avec une base **v2** de 3 sources, 50 204 chaînes et 2 favoris. L'utilisateur manipule le téléphone et saisit lui-même ses identifiants ; l'état est lu par `uiautomator dump`, captures d'écran, `dumpsys` et copie de la base via `run-as`. La colonne Résultat a été remplie au fil des scénarios, entre 16:31 et 19:43.

| # | Scénario | Comment | Attendu | Résultat |
|---|---|---|---|---|
| 1 | Mise à jour et migration 2 → 3 | Gestionnaire de fichiers → Téléchargements → toucher l'APK, confirmer. Lancer l'app, chronométrer jusqu'au résumé de l'Accueil | `lastUpdateTime` du jour ; `user_version` 3 ; les 3 sources, 50 204 chaînes et 2 favoris toujours là ; Films et Séries affichent « Aucun fournisseur Xtream » | **OK** 16:31 : installé depuis Téléchargements ; processus lancé à 16:32:51,76, base écrite en v3 à 16:32:52,07 (migration < 1 s : schéma seul, aucune réécriture des 50 204 lignes) ; `Displayed` +420 ms ; 12 tables et 11 index conformes à `3.json` ; Accueil « 50204 chaînes dans 530 catégories, depuis 3 source(s) », 2 favoris ; Films : « Aucun fournisseur Xtream » et bouton « Ajouter un fournisseur » |
| 2 | Test de connexion refusé | Sources → « Fournisseur Xtream » → serveur, identifiant, mot de passe **faux** → « Tester la connexion » | « Identifiants refusés… » en rouge, aucune source créée | **OK** (joué par l'utilisateur, 16:35–16:38) ; aucune source créée en base avant l'ajout |
| 3 | Test de connexion accepté | Corriger le mot de passe → « Tester la connexion » | « Compte active jusqu'au … · n/m connexion(s) · formats … », rien n'est enregistré | **OK** (joué par l'utilisateur) |
| 4 | Ajout du fournisseur | « Ajouter » ; chronométrer | « N chaînes, M films et S séries importés depuis « … » » ; fiche source : « Xtream · http://hôte:port · identifiant », date de fin de compte, compteurs. En base : `encryptedPassword` commence par `v1:`, aucune occurrence du mot de passe dans `miptv.db` ni dans `logcat` | **OK** : « 28551 chaînes, 161342 films et 63798 séries importés depuis « … » » (fournisseur de test réel, hôte et identifiant non consignés) ; import terminé à 16:39:21, environ 1 min après la dernière saisie ; en base : source 4 `XTREAM`, `encryptedPassword` = `v1:` + 48 caractères, `expiresAt` 17/02/2027, 78 755 chaînes au total dont 28 551 `xtream://`, 958 catégories, 161 342 films, 63 798 séries, 301 catégories VOD ; aucune URL avec identifiants en base ; logcat : aucune ligne de l'app avec `player_api`, `password=` ou chemin `/live/…` (les seules occurrences de « password » sont celles de Gboard). Base 118 Mo + WAL 105 Mo ; mémoire juste après l'import : PSS 520 Mo (Java 161 Mo, natif 244 Mo), à revérifier en régime établi. Remarque : la lecture d'écran par `uiautomator` capture le mot de passe lorsque « Afficher » est actif ; captures supprimées |
| 5 | TV avec les chaînes Xtream | Onglet TV : catégories du fournisseur, filtre, lecture d'une chaîne, plein écran, rotation, retour | Logos, lecture dans les 20 s, « Chargement… » puis commandes ; `dumpsys audio` montre la piste `started` ; aucune URL dans `logcat` | **OK** (joué par l'utilisateur) : chaînes Xtream présentes dans TV (78 755 chaînes au total), lecture d'une chaîne observée côté système par la création puis la libération d'un `AudioTrack` de l'app ; aucune URL de flux ni identifiant dans logcat (seules les lignes du décodeur, URL absentes) |
| 6 | Films : grille et fiche | Films → chips de catégories, filtre par titre, toucher une affiche | Grille 3 colonnes avec affiches Coil, défilement fluide (`dumpsys gfxinfo`) ; fiche avec affiche, année · durée · note, synopsis chargé en moins de 3 s | **OK** : `gfxinfo` depuis la remise à zéro (TV, grilles Films et Séries sur 161 342 films et 63 798 séries, fiches, lecteur) : 15 323 images, **2,4 % saccadées**, médiane 6 ms, 90ᵉ centile 13 ms, 99ᵉ 20 ms ; 2 fiches film complétées depuis le fournisseur (`detailsLoadedAt`) ; mémoire en régime établi après les lectures : PSS 320 Mo (Java 99 Mo, natif 49 Mo), contre 520 Mo juste après l'import |
| 7 | Lecture d'un film et reprise | « Lire », attendre 30 s, avancer et reculer de 10 s avec le contrôleur, plein écran, fermer le lecteur (croix), rouvrir la fiche | Lecture démarre ; ±10 s effectifs ; à la réouverture « Reprendre à N min » et « Recommencer » ; « Reprendre » repart à la position | **OK** (joué par l'utilisateur) : ligne `progress` du film à 81 s sur 1 227 s, écrite à 16:51:35 à la fermeture du lecteur ; décodeurs `c2.android.aac.decoder` (audio) et `c2.qti.avc.decoder` (vidéo H.264 matériel), piste audio créée à 16:49:26 et libérée à 16:50:46 ; aucun crash |
| 8 | Séries : saisons, épisodes, suivant | Séries → fiche → changer de saison → lire un épisode → « Épisode suivant » | Chips de saison avec compteurs ; liste d'épisodes (miniature, durée) ; lecture ; le bouton enchaîne sur l'épisode suivant (titre « S01E02 · … ») ; à la fin réelle d'un épisode court, le bouton apparaît aussi en plein écran | **OK** (joué par l'utilisateur) : 1 série détaillée en cache (`seasons` 1, `episodes` 20) ; trois épisodes consécutifs lus par « Épisode suivant » avec positions écrites à 16:52:57, 16:53:04 et 16:53:14 (19 s, 5 s puis 1 235 s sur 3 240 s après un saut avant) ; chaque enchaînement libère la piste audio précédente et en crée une nouvelle (un seul lecteur). Fin réelle d'un épisode non observée (épisodes de 50 min) |
| 9 | Accueil : Reprendre et récents | Revenir à l'Accueil après 7 et 8 | Rangée « Reprendre » (film et épisode, barre de progression, « Reprendre à N min »), « Films récents », « Séries récentes » ; toucher une carte Reprendre ouvre la fiche avec le lecteur à la position | **OK** (joué par l'utilisateur) : en base, l'épisode repris depuis l'Accueil est réécrit à 16:56:12 (1 238 s, soit la position sauvegardée + 3 s de lecture) et un second film repris à 16:58:56 (1 294 s sur 6 693 s) ; `recentMovies`/`recentSeries` ordonnés par `addedAt`/`last_modified` du fournisseur (films du jour en tête) ; fluidité Accueil + Recherche : 7 898 images, 1,5 % saccadées, médiane 6 ms |
| 10 | Recherche groupée | Recherche → un mot présent dans une chaîne, un film et une série | Trois groupes « Chaînes · n », « Films · n », « Séries · n » ; un film ouvre sa fiche dans Films, une série dans Séries, une chaîne joue dans TV | **OK** (joué par l'utilisateur) : une troisième fiche film complétée depuis le fournisseur pendant ce scénario (`detailsLoadedAt` : 3 films) ; pistes audio ouvertes puis libérées à 16:58:45 et 16:58:53 |
| 11 | Favoris films et séries | Étoile sur une affiche et dans une fiche ; chip « Favoris » dans Films et Séries | Les favoris apparaissent sous « Favoris » dans chaque onglet ; en base, lignes `favorites` avec `xtream:…:movie:` et `xtream:…:series:` | **Partiel** : un favori film en base (`xtream:4:movie:…`, 3 favoris au total avec les 2 chaînes M3U) ; aucun favori série posé pendant le scénario, à refaire au scénario 13 avant l'actualisation pour vérifier sa conservation |
| 12 | Redémarrage sans réimport | `am force-stop com.miptv` (adb) puis relance | Catalogue, favoris, « Reprendre » intacts ; `lastRefreshed` inchangé ; la reprise d'un film repart bien à sa position (Keystore relu) | **OK** (adb, 17:00) : `am force-stop` puis `am start -W` : Accueil affiché en **453 ms** avec « 78755 chaînes, 161342 films et 63798 séries, depuis 4 source(s) », rangée « Reprendre » (film « … · Reprendre à 21 min », épisode « S01E03 · … · Reprendre à 20 min ») et « Films récents » ; en base, 3 favoris et 5 positions inchangés, `lastRefreshed` du fournisseur toujours 16:39:21. Lecture après relance (clé Keystore relue) : voir scénario 16 |
| 13 | Actualisation du fournisseur | Fiche source → « Actualiser » ; chronométrer | Compteurs et date mis à jour ; favoris et positions conservés ; rouvrir une série recharge ses saisons (cache vidé puis rempli) | **Rapporté par l'utilisateur** (rejoué vers 17:16 après la première passe, sans observation adb) : compteurs et date mis à jour, favoris et positions conservés. La première passe (avant 17:11) n'avait laissé aucune trace en base et la seconde n'a pu être lue : la copie de la base prise pendant l'actualisation était incohérente (écriture en cours), puis le mode avion du scénario 15 a coupé la liaison adb Wi-Fi et la suppression du scénario 17 a effacé la fiche source, donc `lastRefreshed` et `lastError` du fournisseur. Seule trace indirecte : la source iptv-org porte `lastRefreshed` 17:16:48, donc une actualisation a bien été lancée depuis l'écran Sources à ce moment |
| 14 | Arrière-plan pendant un film | Lancer un film, bouton Accueil du téléphone, revenir | Lecture en pause au retour, reprise manuelle ; position sauvegardée (vérifiable dans `progress`) | **Rapporté par l'utilisateur** ; sans trace exploitable après le redémarrage (journaux perdus). Les positions des lectures suivantes sont bien en base |
| 15 | Réseau coupé | Mode avion → « Actualiser » puis « Lire » un film | Erreur en français sur la source (« Hôte introuvable… ») et dans le lecteur (« Connexion au flux impossible… » puis « Réessayer ») ; aucun hôte, identifiant ni mot de passe dans les messages | **Rapporté par l'utilisateur** (rejoué vers 17:21) : « Actualiser » affiche l'erreur réseau en français sur la fiche source, « Lire » affiche « Connexion au flux impossible… » puis « Réessayer », sans hôte ni identifiant dans les messages. Non observable par adb : le mode avion coupe le débogage Wi-Fi (dernière lecture à 17:21:17, liaison perdue à 17:21:32), et la fiche source où `lastError` aurait été lue a été supprimée au scénario 17 avant le retour du débogage (19:43). Les textes affichés sont ceux vérifiés par `XtreamParserTest`, `XtreamClientTest` et `PlayerTimeoutTest` |
| 16 | Redémarrage du téléphone | Redémarrer, relancer l'app, lire un film | La clé Keystore survit : lecture possible sans ressaisie | **OK** : redémarrage vers 17:08 (`uptime` 3 min à 17:11), processus lancé à 17:09:53, piste audio de l'app créée à 17:10:05 et libérée à 17:10:08, position de l'épisode écrite à 17:10:08 (1 160 s sur 2 650 s) : l'URL a donc été résolue avec le mot de passe déchiffré par la clé Keystore relue. Accueil « Reprendre à 19 min » / « Reprendre à 21 min ». Aucune URL ni identifiant dans logcat depuis le redémarrage. (Une exception fatale à 17:08:43 appartient à l'application franceinfo, sans rapport) |
| 17 | Suppression du fournisseur | Fiche source → « Supprimer » | Films et Séries reviennent à « Aucun fournisseur Xtream » ; TV ne garde que les chaînes M3U ; `favorites` et `progress` du fournisseur vides ; tables `movies`, `series`, `seasons`, `episodes`, `vod_categories` vides | **OK** (base relue à 19:43 au retour du débogage sans fil, `integrity_check` ok) : plus aucune source `XTREAM` ; `channels` ne contient plus aucun identifiant `xtream:` ni référence `xtream://` ; `categories` sans entrée `xtream:` ; `movies`, `series`, `vod_categories`, `seasons`, `episodes` et `progress` vides (les 6 positions du fournisseur ont disparu) ; `favorites` ne garde que les 2 chaînes M3U (BFM Business, RMC Story), les 2 favoris film et série du fournisseur sont partis. Il ne reste que la source iptv-org (198 chaînes, 29 catégories) : la playlist de 50 000 chaînes avait été supprimée à la première passe et la source fichier `miptv-test.m3u` (6 chaînes) l'a été pendant cette passe. La première passe (avant 17:11) avait supprimé la playlist de 50 000 chaînes au lieu du fournisseur : ses 50 000 chaînes et 100 catégories avaient bien disparu (base de 118 à 105 Mo) et le fournisseur était resté intact (28 551 chaînes, 161 342 films, 63 798 séries, 2 favoris, 6 positions), ce qui valide aussi la suppression ciblée d'une source M3U au milieu d'autres |

Hors de portée sans second appareil : la perte de clé après restauration (scénario « Identifiants à ressaisir »), couverte par `XtreamRepositoryTest`.

## Sprint 3 — lot 0.2 : performance du catalogue (29 octobre 2026)

Mesures sur une playlist générée de **50 000 chaînes** (500 groupes de 100, `tvg-id`, logo, URL), émulateur local Pixel 2 **API 35 x86_64** sur un MacBook Intel i7-1068NG7, APK **debug**. Les valeurs absolues sont pessimistes : un parsing de 100 000 lignes y prend environ 3 s, quelques centaines de millisecondes sur un ordinateur. Les comparaisons avant/après sont faites sur la même machine.

### Couche données (`CatalogPerformanceTest`, 3 exécutions)

| Étape | Avant (`8a99470`) | Après |
| --- | --- | --- |
| Parsing seul | 6,8 – 7,5 s | 2,8 – 3,1 s |
| Import complet (`addSource`) | 20,6 – 24,6 s | 19,9 – 24,6 s |
| Premier affichage | 4,1 – 6,7 s (tout le catalogue lu et converti) | 0,13 – 0,17 s base chaude ; jusqu'à 5,3 s juste après l'import |
| Mémoire retenue par le catalogue | +17 Mo | aucune liste complète en mémoire |
| Favori coché | 64 – 78 ms (catalogue entier réémis) | 19 – 29 ms (seuls la liste et le compteur « Favoris ») |
| Filtre « Chaîne 4999 » | 107 – 114 ms (en mémoire) | 148 – 212 ms (compte SQL + première page) |
| Changement de catégorie | 34 – 37 ms | 51 – 83 ms (compte + première page) |
| 84 pages jusqu'à la chaîne 5 000 | — | 190 – 246 ms |

- **Écriture.** L'insertion SQLite brute des 50 000 lignes (instruction compilée, une transaction) prend à elle seule 13,6 – 15 s. Elle ne bouge pas si les lignes sont triées par identifiant, avec un cache de 32 Mo ou avec `synchronous=OFF` : l'émulateur est limité par le CPU, pas par le disque. L'import n'a donc gagné que sur le parsing, dont l'expression régulière par ligne a été remplacée par une lecture manuelle des attributs. Ce gain est en partie repris par le calcul de la colonne `searchName` et par un index plus large.
- **Plans SQLite** (`EXPLAIN QUERY PLAN`) : la première page parcourt `index_channels_sourceId_position`, une page suivante fait `SEARCH … USING INDEX index_channels_sourceId_position ((sourceId,position)>(?,?))`, et le compte sans filtre utilise un index couvrant.
- **Pagination Room écartée.** Une première version avec la source `LIMIT/OFFSET` de `room-paging` mettait 3 à 13 s pour le premier écran, avec un `COUNT(*)` complet à chaque chargement. Elle a été remplacée par la pagination par clé.
- **Juste après un import**, la première lecture coûte parfois jusqu'à 5 s sur l'émulateur, contre 0,15 s ensuite. La cause n'a pas été isolée (ramasse-miettes ou cache SQLite après l'écriture de 50 000 lignes) ; elle est à vérifier sur appareil.

### Application (import par l'interface, `dumpsys gfxinfo`)

Même playlist servie depuis le Mac (`10.0.2.2`). L'application de référence est construite depuis `8a99470`. La nouvelle version est installée par-dessus, ce qui exerce la migration v1 → v2 sur 50 000 chaînes. Les durées incluent la scrutation par `uiautomator dump`, qui ajoute 1 à 3 s.

| Mesure | Avant | Après |
| --- | --- | --- |
| Import (toucher « Ajouter » → message) | 36,7 s | 32,2 s |
| Premier démarrage après mise à jour (migration) | — | 14,2 s jusqu'au résumé de l'Accueil (≈ 7 s de migration) |
| Démarrage à froid → résumé de l'Accueil | 7,1 – 7,4 s | 6,5 – 7,1 s |
| Défilement rapide, 15 gestes sur « Toutes » | 97 – 98 % d'images saccadées, médiane 89 – 93 ms | 98 % d'images saccadées, médiane 81 – 101 ms |

**La fluidité n'est pas mesurable sur cet émulateur.** Le rendu y est purement logiciel (SwiftShader via ANGLE) et l'APK est en debug, donc chaque image prend environ 90 ms dans les deux versions, même avant la 80ᵉ chaîne. La mesure du défilement est reportée au Xiaomi 15, avec la dette de validation sur appareil (lot 0.3).

**Bilan des critères du lot 0.2 :**
- chaînes chargées par pages et par catégorie : fait ;
- un favori ne réémet plus le catalogue : fait (test `starringAChannelInvalidatesOnlyTheFavouritesList`) ;
- temps d'import et mémoire : mesurés ;
- défilement : non concluant sur émulateur ; mesuré sur appareil au lot 0.3.

## Sprint 3 — lot 0.3 : validation sur le Xiaomi 15 (29 octobre 2026)

**Matériel et méthode.** Xiaomi 15 (`24129PN74G`, HyperOS 3 / Android 16), écran de 1200×2670. Au départ, l'APK debug du Sprint 2 (schéma v1) était installé, avec la source iptv-org (198 chaînes, 2 favoris). La mise à jour est l'APK debug 0.3.0 du lot 0.2 (`7836642`, avec `versionCode` 3). Elle a été installée depuis le gestionnaire de fichiers, et la confirmation de l'installeur MIUI faite par l'utilisateur.

**Pilotage.** HyperOS refuse `input` ; les gestes passent donc par des scripts `monkey` (`Tap`, `Drag`, touches du clavier Gboard). La lecture de l'état passe par `uiautomator dump`, `dumpsys gfxinfo`, `dumpsys audio`, `dumpsys meminfo` et une copie de la base par `run-as`. Les playlists sont servies depuis le Mac sur le réseau local. Une scrutation `uiautomator` prend environ 2,5 s, ce qui borne la précision des durées marquées « ≤ ».

### Volume et mise à jour

Source de 50 000 chaînes importée par adresse dans la v1. Elle a été importée deux fois par erreur (deux touchers sur « Ajouter »), soit 100 198 chaînes au total.

| Mesure | v1 (Sprint 2) | v2 (0.3.0) |
| --- | --- | --- |
| Import de 50 000 chaînes (requête → fin du parsing) | ≈ 2 s ; < 8 s jusqu'au message | — |
| Démarrage à froid, première image | 383 – 443 ms | 378 – 413 ms |
| Premier démarrage après mise à jour (migration v1 → v2 sur 100 198 chaînes) | — | 3,1 s jusqu'au résumé de l'Accueil, comme un démarrage ordinaire (migration ≲ 1 s) |
| Tas Java / PSS total | 71 Mo / 216 Mo | 25 Mo / 154 Mo |
| Défilement, 15 gestes sur « Toutes » : images saccadées | 5,7 – 7,4 % | 4,8 – 8,8 % |
| Durée d'une image : médiane / 90ᵉ / 99ᵉ percentile | 15 – 18 / 42 – 46 / 69 – 89 ms | 8 – 9 / 38 / 61 – 69 ms |
| Même mesure avec 198 chaînes seulement (v1) | 0 % ; 5 / 10 / 23 ms | — |

- **Migration** : `user_version` passe à 2, les 3 sources, les 100 198 chaînes et les 2 favoris sont conservés, `searchName` est rempli partout (« Chaîne 0 » → `chaine 0`) et les nouveaux index sont en place.
- **Défilement** : la v2 divise la mémoire par trois et la durée médiane d'une image par deux. Les saccades restantes ne diminuent pas, alors que le catalogue n'est plus en mémoire. L'hypothèse, non vérifiée, est le chargement des logos (servis depuis le Mac) et la composition des cartes à l'arrivée de chaque page.

### Scénarios

| Scénario | Résultat |
| --- | --- |
| Adresse dont l'hôte n'existe pas (faute de frappe) | « Hôte introuvable. Vérifiez l'adresse et la connexion. » après ≈ 29 s (délai DNS), aucune source ajoutée |
| Import d'un fichier par le sélecteur (`miptv-test.m3u`, Téléchargements) | « 3 chaînes importées depuis « miptv-test.m3u » » |
| Filtre « flux » saisi au clavier | « Chaînes · 1 », la chaîne de test |
| Lecture d'un flux absent (HTTP 404 du serveur) | « Le serveur a refusé le flux (HTTP 404). » et « Réessayer », sans l'adresse du flux |
| « Réessayer » après mise à disposition du flux | Nouvelle requête (200), image affichée, piste audio de l'application `started` |
| Arrière-plan pendant la lecture, puis retour | Piste `paused` en arrière-plan ; au retour, même chaîne sélectionnée, toujours en pause ; lecture relancée par les commandes tactiles (`started`) |
| Suppression d'une source de 50 000 chaînes | Liste à jour en ≤ 2,3 s ; 50 203 chaînes restantes |
| Fichier remplacé sur le téléphone (`adb push`) puis « Actualiser » | « Fichier inaccessible. Sélectionnez-le à nouveau. » ; les 3 chaînes précédentes sont conservées. Android retire l'autorisation d'accès au document supprimé ; c'est le cas de la permission révoquée |
| Fichier modifié sur place puis « Actualiser » | « 5 chaînes importées », date d'actualisation mise à jour |
| Actualisation réseau de la source iptv-org | « 198 chaînes importées » en ≤ 2,3 s ; les 2 favoris (BFM Business, RMC Story) sont conservés |
| Redémarrages à froid successifs | Catalogue restauré sans aucune requête au serveur (pas de réimport) |

Les points « Non vérifié sur appareil » du Sprint 2 (ci-dessous) sont ainsi tous couverts : erreur et nouvel essai, fichier local, actualisation et suppression, arrière-plan, dizaines de milliers de chaînes. Le plein écran en paysage avait été validé au Sprint 2.

### Constats à traiter

- **Fichier remplacé.** Une playlist retéléchargée par-dessus l'ancienne rend la source fichier inaccessible. La seule issue est de la supprimer et de la rajouter. La nouvelle source reçoit un autre identifiant, et les identifiants de chaînes l'incluant, ses favoris sont perdus. Il manquerait un « Sélectionner à nouveau » qui garde la source.
- **Doublons de sources.** Rien n'empêche d'ajouter deux fois la même adresse, ce qui double les chaînes. Les identifiants de sources sont aussi réutilisés après une suppression (`MAX(id) + 1`).
- **Installation.** La première mise à jour n'a pas été appliquée : l'installeur s'était ouvert sur l'ancienne entrée MediaStore de `miptv.apk`, le fichier ayant été redéposé sous le même nom. Le `versionCode` identique n'y est pour rien : au lot 0.4, un APK de même `versionCode` (3), déposé sous un nouveau nom, a bien été installé. Le `versionCode` passe tout de même à 3 (`versionName` 0.3.0) avec le changement de schéma, et l'APK est désormais déposé sous un nouveau nom.

## Sprint 3 — lot 0.4 : délai de chargement borné (29 octobre 2026)

Un flux qui ne répond pas restait sur « Chargement… » : Media3 relance seul les erreurs réseau, et un serveur qui accepte la connexion sans rien envoyer le fait attendre sans fin. Désormais, après 20 s de chargement continu alors que la lecture est demandée (au démarrage comme sur un direct qui se fige), le lecteur s'arrête et affiche « Le flux ne répond pas après 20 secondes. » avec « Réessayer ». Une pause, y compris le passage en arrière-plan, arrête le décompte.

### Tests

- Unitaires : 32/32, dont deux sur `LoadingWatch` (expiration à la limite, remise à zéro après une pause ou la fin du chargement).
- Instrumentés : 15/15 sur l'émulateur, dont `PlayerTimeoutTest` : un vrai lecteur Media3 face à un serveur local qui garde la connexion ouverte sans répondre, limite réduite à 3 s. L'erreur apparaît, « Réessayer » relance un chargement, interrompu à son tour.
- Lint : 29 avertissements, aucun nouveau, aucune erreur.

### Xiaomi 15

Chaîne « Flux muet » ajoutée au fichier de test, pointée vers un serveur du Mac qui accepte la connexion et n'envoie rien. Les temps sont mesurés par `uiautomator dump`, donc à environ 2,5 s près.

| Scénario | Résultat |
|---|---|
| Lecture depuis la recherche | « Chargement… », puis le message et « Réessayer » entre 20 et 22,6 s après le tap |
| « Réessayer » | Nouveau chargement, à nouveau interrompu au plus 22,4 s après le tap |
| Arrière-plan 5 s après le lancement, 30 s dehors, puis retour | Aucun message des 20 s pendant la pause. Le lecteur reste en pause au retour. Media3 continue toutefois de charger pendant la pause et finit par échouer de lui-même (« Connexion au flux impossible. Vérifiez votre réseau. » et « Réessayer ») 66 à 76 s après le lancement, ce qui reste une fin bornée |

## Sprint 3 — lot 1 : client Xtream et modèle (29 octobre 2026)

Lot testable sans appareil ni compte : aucun réseau, réponses JSON enregistrées et anonymisées (`XtreamFixtures`).

- Unitaires : **65/65** (32 existants, 33 nouveaux : 9 `JsonStreamReaderTest`, 18 `XtreamParserTest`, 6 `XtreamClientTest`).
- 30 000 films (`get_vod_streams` généré, ≈ 8,5 Mo de JSON) parsés en flux en **0,44 s** sur le Mac ; identifiants uniques, catégories et années conservés.
- `HttpXtreamClient` exercé contre un serveur local `ServerSocket` : identifiants transmis uniquement en paramètres de requête, redirection suivie en gardant la requête, page HTML et HTTP 503 traduits en français, hôte injoignable sans écho de l'adresse.
- Aucun message d'erreur (refus, expiration, HTTP 401/403/404/429/5xx, hôte introuvable, URL malformée, `IOException` brute, exception inconnue) ne contient le serveur, l'identifiant ou le mot de passe.
- Lint : 29 avertissements, aucun nouveau, aucune erreur. Schéma Room inchangé.

Écarts par rapport au plan, consignés dans `SPRINT3.md` : lecteur JSON maison au lieu d'`android.util.JsonReader` (stub dans les tests locaux), et référence `xtream://` stockée dans `Channel.streamUrl` pour que la table `channels` ne contienne jamais d'identifiant.

## Sprint 3 — lot 2 : identifiants chiffrés et persistance Room v3 (29 octobre 2026)

Toujours sans réseau ni compte : faux client Xtream (`FakeXtreamClient`, réponses enregistrées passées dans les vrais parseurs) et clé AES générée sur place à la place du Keystore.

- Unitaires : **74/74** (9 nouveaux : 3 `AesGcmCipherTest`, 4 `MemoryXtreamRepositoryTest`, 2 `XtreamViewModelTest`).
- Instrumentés : **22/22** sur l'émulateur Pixel 2 API 35 (`CatalogPerformanceTest` ignoré sans `-e perf true`), dont 6 `XtreamRepositoryTest` et `MigrationTest` v1 → v2 → v3. Première passe : un échec dû au test lui-même (une catégorie Xtream sans chaîne est purgée par Room comme un groupe M3U vide) ; attente corrigée, 7/7 à la relance des deux classes Room.
- Vérifié en base : la colonne `encryptedPassword` commence par `v1:` et ne contient pas le mot de passe ; `playbackUrl` reconstruit `…/live/u-test/p-test/101.ts` ; clé perdue → actualisation en échec « Identifiants à ressaisir. », catalogue conservé, `playbackUrl` nul ; compte refusé ou expiré à l'actualisation → erreur sur la source, catalogue conservé ; fiches film et série demandées une fois puis servies du cache, cache vidé par l'actualisation ; favoris et positions de reprise conservés à l'actualisation, supprimés avec la source ; après suppression, les dix tables sont vides.
- Migration : les instructions de `MIGRATION_2_3` sont celles du schéma exporté `3.json` (vérifié par script) ; `MigrationTestHelper` valide colonnes, valeurs par défaut (`movieCount`, `seriesCount` à 0) et index.
- Lint : **28 avertissements** (29 avant : `DataExtractionRules` résolu par les règles de sauvegarde), aucune erreur.

Non vérifié ici : le Keystore réel (clé matérielle, perte après restauration) et l'exclusion effective du transfert d'appareil à appareil, qui demandent un appareil et un compte ; prévus avec la validation du lot 3 sur le Xiaomi 15.

## Sprint 3 — lot 3 : interface Xtream, Films, Séries, lecteur VOD (29 octobre 2026)

Sans réseau ni compte : faux client Xtream et dépôt mémoire derrière la vraie interface Compose.

- Unitaires : **80/80** (6 nouveaux dans `XtreamViewModelTest`, 8 au total : ajout, formulaire incomplet ou refusé, test de connexion, catégories et filtres restaurés, fiche film et reprise, fiche série avec saisons et « Épisode suivant », recherche groupée, lecteur unique et fermeture des fiches avec leur source).
- Instrumentés : **26/26** sur l'émulateur Pixel 2 API 35 (`CatalogPerformanceTest` ignoré sans `-e perf true`), dont 4 `XtreamPhoneTest` : formulaire Xtream avec test de connexion refusé puis accepté, ajout et fiche source (expiration), mot de passe absent de l'écran ; grille Films → fiche (synopsis, durée) → « Lire » → lecteur → plein écran → fermeture ; fiche Séries → saison 2 → saison 1 → épisode → « Épisode suivant » ; rangée « Reprendre » de l'Accueil → fiche avec « Reprendre à 42 min ». Première passe : un échec dans un test du Sprint 2 (« Aucune source » passé sous le pli par le nouveau choix M3U / Xtream) ; défilement ajouté, 6/6 à la relance.
- Incident de développement : une première version des tests ViewModel lisait les listes paginées `cachedIn` du ViewModel sous `runBlocking`, ce qui bloque avec le `StandardTestDispatcher` ; les tests lisent désormais le dépôt pour les requêtes du modèle, comme ceux du Sprint 2.
- Lint : **28 avertissements**, aucun nouveau, aucune erreur. Schéma Room inchangé (v3).

Non vérifié ici, à faire sur le Xiaomi 15 avec un compte de test : lecture réelle d'un film et d'un épisode Xtream (résolution de l'URL, saut ±10 s, reprise après fermeture), « Épisode suivant » à la fin réelle d'un épisode, affiches Coil sur un vrai catalogue, Keystore réel, fluidité des grilles sur plusieurs milliers de films.

## Sprint 3 — lot 4 : tests, CI et documentation (29 octobre 2026)

- Tests ajoutés : `FormatTest` (6 unitaires : durées et « Reprendre à », sous-titre de film, titres d'épisode et épisode suivant, seuil « Vu » à 95 %, filtre VOD insensible aux accents, description du compte) et `PlayerResolutionTest` (instrumenté : identifiants absents → « Identifiants de la source à ressaisir dans Sources » avec « Réessayer », nouvel essai qui résout à nouveau la référence, URL résolue atteignant un serveur local muet puis délai borné, fermeture du lecteur).
- Couverture du plan vérifiée : parseurs, URL, messages, chiffrement, ViewModel Films et Séries (unitaires) ; migration 2 → 3, ajout Xtream par faux client, Films → fiche → lecture, Séries → saison → épisode, reprise (instrumentés). Non automatisable : la fin réelle d'un épisode et la sauvegarde de position par le vrai lecteur, qui demandent un média lisible ; couvertes par les tests du ViewModel et à observer sur appareil.
- CI inchangée (`android.yml`) : unitaires, lint, APK et APK de test, vérification du schéma exporté, émulateur Pixel 2 API 35. Les fixtures partagées (`src/sharedTest`) sont compilées dans les deux source sets par Gradle, sans étape supplémentaire.
- Unitaires : **86/86**. Instrumentés : **27/27** (`CatalogPerformanceTest` ignoré sans `-e perf true`). Lint : 28 avertissements, aucune erreur.
- `README.md` réécrit pour le Sprint 3 (fonctionnalités Xtream, architecture, choix de conception, sécurité, tests) ; ce fichier réorganisé par lot avec la synthèse ci-dessus.

## Sprint 2

Résultats du 28 octobre 2026, code vérifié au commit `7457edb` (lot 5 ; les commits suivants ne touchent que la documentation) :

| Vérification | Résultat |
| --- | --- |
| Tests unitaires `testDebugUnitTest` | 28 réussis (8 parseur M3U, 18 ViewModel, 2 erreurs de lecture), zéro échec |
| Analyse Android `lintDebug` | Réussie, zéro erreur, 24 avertissements |
| Compilation APK debug `assembleDebug` | Réussie en local |
| Compilation APK des tests `assembleDebugAndroidTest` | Réussie en local |
| Tests instrumentés `connectedDebugAndroidTest` | 10 réussis (6 Compose, 4 Room) sur émulateur local Pixel 2 **API 30 x86** (seule image disponible hors ligne) |
| `git diff --check` | Réussi |

Environnement local macOS (Intel), JDK 21, SDK 35, Build Tools 35.0.0. La commande CI complète `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` a été exécutée après chaque lot et une dernière fois sur l'état final. L'exécution distante GitHub Actions (émulateur API 35) est déclenchée par la PR ; son résultat est consigné dans la PR.

Les 24 avertissements lint concernent les mises à jour de dépendances/SDK disponibles (volontairement épinglées), la configuration de sauvegarde (`allowBackup` déprécié), l'écran tactile volontairement requis et un `InputStream` fermé par l'appelant. Un premier échec lint (`ByteOrderMark`) causé par un BOM littéral dans le parseur et son test a été corrigé en l'écrivant comme séquence d'échappement `﻿`.

### Lots et commits

| Lot | Commit | Contenu |
| --- | --- | --- |
| Dépendances | `ea26b24` | Room 2.6.1 via KSP 2.0.21-1.0.28, Coil 2.7.0, coroutines 1.9.0 (+ test) |
| 2 — Parsing | `6d2d9cb`, `f33ea22` | `M3uParser` en flux, 8 tests (25 000 chaînes incluses) |
| 1 — Sources | `4cc0807` | Modèle `Source`, contrat asynchrone, chargeur URL/SAF, dépôt mémoire, écran Sources |
| 3 — Catalogue | `1f17177` | TV à partir des chaînes importées, logos Coil, filtre, état sans source |
| 4 — Lecture | `54aa6b0` | Lecteur hissé, plein écran paysage, erreurs Media3 en français, nouvel essai |
| 5 — Persistance | `7457edb` | Room (sources, catégories, chaînes, favoris), catégorie « Favoris » |

Le lot 2 (parseur) a été commité avant le lot 1 parce que l'importation d'une source en dépend ; chaque commit compile et passe ses tests unitaires. Lint n'a été exécuté qu'à partir du lot 1 (le correctif `f33ea22` rend le lot 2 conforme).

### Ce qui a été vérifié

- Parseur : attributs `#EXTINF`, valeurs manquantes (repli titre → `tvg-name` → nom de fichier), lignes vides, BOM, commentaires et directives inconnues, `#EXTVLCOPT` rattaché à la chaîne suivante uniquement, CRLF, attributs sans guillemets, titres et groupes contenant des virgules, identifiants stables entre deux imports, unicité en cas de doublon, fusion des groupes insensible à la casse, 25 000 chaînes depuis un `Reader`.
- ViewModel : catégories « Toutes »/« Favoris »/importées, repli vers « Toutes » et abandon de la sélection lorsque la catégorie ou la chaîne disparaît à l'actualisation, recherche et filtre, restauration de l'onglet/catégorie/recherche/filtre/écran Sources sans autoplay, rejet d'une chaîne falsifiée, adresse non `http(s)` refusée sans appel réseau, hôte injoignable, actualisation en échec conservant les chaînes et enregistrant l'erreur, suppression, plein écran invalide sans sélection et terminé avec elle, favoris conservés après actualisation.
- Lecture : correspondance code d'erreur → message français ; les messages ne contiennent jamais l'adresse du flux.
- Instrumenté (Compose) : cinq onglets, filtrage par catégorie et recherche, écran Sources (adresse invalide puis import valide), état sans source menant à l'ajout puis filtre, aperçu + entrée/sortie du plein écran, étoile et catégorie Favoris. (Room) : import persistant relu par un second dépôt, favoris conservés à l'actualisation, échec conservant les chaînes, suppression purgeant catégories et favoris, échec d'ajout sans source créée.

Sur la CI (émulateur Pixel 2 API 35), le test `sourcesScreenAddsUrlAndRejectsInvalidAddress` a échoué trois fois : sur API 33+, le clavier logiciel reste affiché après `performTextInput`, couvre le bouton « Ajouter l'adresse » (le clic atteint l'IME) et réduit la zone composée de la `LazyColumn`. Reproduit en local sur un AVD API 35 x86_64, corrigé en fermant le clavier (`Espresso.closeSoftKeyboard()`) après chaque saisie et en remplaçant l'adresse en une seule action ; 10/10 sur les AVD API 30 et API 35.

Première exécution instrumentée : 5 échecs de mécanique de test (`performScrollTo()` sur des éléments `LazyColumn` non encore composés, clic sur un bouton hors écran, sélecteur de texte ambigu entre le champ de saisie et la fiche source), corrigés en faisant défiler la liste jusqu'à l'élément (`performScrollToNode`) et en ciblant les `testTag`. Deuxième exécution : 10/10. Pendant cette exécution, le Xiaomi 15 s'est connecté en débogage Wi-Fi et Gradle a tenté d'y installer l'APK : refus HyperOS `INSTALL_FAILED_USER_RESTRICTED` connu, sans rapport avec le code ; l'APK debug a été déposé dans `/sdcard/Download/miptv.apk` pour installation manuelle.

### Validation sur téléphone physique (Xiaomi 15, HyperOS 3 / Android 16)

Le 29 octobre 2026, APK debug du commit `3167fcc` installé depuis Téléchargements (validation manuelle de l'installateur MIUI), piloté par scripts `monkey` (touches dans la fenêtre de l'app et sur le clavier Gboard), lu par `uiautomator dump`, captures d'écran et copie de la base Room via `run-as`.

| Scénario | Résultat |
| --- | --- |
| Ajout d'une source par adresse (`iptv-org.github.io/iptv/countries/fr.m3u`) | « 198 chaînes importées », date d'actualisation affichée, hôte seul affiché |
| Catalogue TV | 29 catégories (« Toutes », « Favoris » + 27 groupes), 198 chaînes, 197 logos chargés par Coil, défilement fluide |
| Filtre et catégorie | « france » → 21 chaînes ; catégorie General → 27, avec filtre → 7 ; effacement du filtre OK |
| Lecture HLS | BFM Business 1080p lu par le décodeur matériel `c2.qti.avc.decoder`, commandes tactiles affichées |
| Plein écran | Paysage (`ROTATION_90`), barres système masquées, le même lecteur continue (horodatage chaîne continu) ; sortie par la croix, retour portrait, lecture toujours active |
| Favoris | Étoile sur la fiche et dans l'aperçu, catégorie « Favoris », ligne présente dans la table `favorites` |
| Persistance | Après `am force-stop` et relance : 198 chaînes restaurées, `lastRefreshed` inchangé (aucun réimport), favori conservé |

Première tentative de lecture (6ter, flux `http://…:9999`) restée en « Chargement… » plus de 30 s puis remplacée par une autre chaîne : le flux répond depuis le Mac mais pas depuis le téléphone dans le délai ; ExoPlayer réessaie avant de signaler l'erreur. Le message d'erreur et le bouton « Réessayer » n'ont donc pas été observés sur appareil (couverts par les tests unitaires et instrumentés).

### Limites

- Non vérifié sur appareil : erreur de lecture et nouvel essai, import depuis un fichier SAF, actualisation et suppression d'une source, arrière-plan/reprise, playlist de plusieurs dizaines de milliers de chaînes.
- Les tests instrumentés n'ouvrent aucun flux réseau ; la lecture réelle (HLS/TS, décodage, plein écran effectif, rotation, arrière-plan) et les logos distants nécessitent une validation manuelle avec une playlist réelle.
- Le sélecteur de fichiers SAF n'est pas automatisable en test ; la révocation d'une permission persistée est traitée par le message « Fichier inaccessible » mais n'a pas été exercée.
- Playlists de plusieurs dizaines de milliers de lignes : parseur vérifié sur 25 000 chaînes en test unitaire ; l'insertion Room par lots de 500 et l'affichage `LazyColumn` n'ont pas été mesurés sur appareil.
- Films/Séries restent fictifs ; `XtreamAdapter` reste une interface. Aucun backend, identifiant fournisseur ou contenu n'est livré.
