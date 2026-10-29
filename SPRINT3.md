# Plan — Sprint 3 smartphone : fournisseur Xtream, Films et Séries

Objectif : remplacer les onglets Films et Séries fictifs par le **vrai catalogue VOD d'un fournisseur Xtream Codes** fourni par l'utilisateur (serveur, identifiant, mot de passe), importer aussi ses chaînes en direct dans l'onglet TV existant, et solder la dette de validation du Sprint 2.

Mêmes règles qu'aux sprints précédents : smartphone uniquement (API 26+), aucun identifiant, flux ou contenu livré avec l'application, aucun backend, aucune télémétrie, aucune adresse ni identifiant journalisé.

## Prérequis

- La PR #4 (Sprint 2) est fusionnée dans `main` (fait le 29 octobre 2026). Le Sprint 3 part de `main`, sur la branche `feature/sprint3-xtream`.
- Un compte Xtream de test, fourni par l'utilisateur, pour la validation sur appareil. Les tests automatisés utilisent uniquement des réponses JSON enregistrées, sans réseau.

## Lot 0 — Dette du Sprint 2

| # | Élément | Critère de fin |
|---|---------|----------------|
| 0.1 | Schéma Room exporté (`exportSchema = true`, dossier `app/schemas` versionné) avant la première migration | Le schéma v1 est dans le dépôt ; un test `MigrationTestHelper` ouvre une base v1 remplie |
| 0.2 | Performance du catalogue : `RoomChannelRepository` émet aujourd'hui **toutes** les chaînes à chaque changement (y compris un simple favori) | Chaînes chargées par catégorie (requête par `categoryId`) ou via Paging 3 ; un favori ne réémet plus le catalogue entier ; mesure sur une playlist de 50 000 chaînes consignée dans `VALIDATION.md` (temps d'import, mémoire, fluidité du défilement) |
| 0.3 | Validations sur appareil non faites au Sprint 2 | Observées sur le Xiaomi 15 et consignées : erreur de lecture puis « Réessayer », import d'un fichier SAF, actualisation et suppression d'une source, arrière-plan puis reprise, redémarrage sans réimport ; import, migration v1 → v2 et fluidité du défilement sur 50 000 chaînes (non mesurable sur l'émulateur, voir `VALIDATION.md`) |
| 0.4 | Lecture d'un flux lent (cas 6ter : plus de 30 s en « Chargement… ») | Délai de chargement borné (environ 20 s) au-delà duquel l'erreur et « Réessayer » s'affichent, au lieu de laisser ExoPlayer réessayer indéfiniment |

## Lot 1 — Client Xtream et modèle

- **Domaine** (`Domain.kt`) : `SourceKind.XTREAM` ; modèles `Movie` et `Series` enrichis (identifiant fournisseur, catégorie, affiche, note, année, synopsis, durée, extension du conteneur) ; `Season` et `Episode`. L'interface `XtreamAdapter` actuelle est remplacée : `XtreamStream.resolvedUrl` disparaît, car **une URL résolue contient les identifiants**.
- **Client** (`Xtream.kt`, nouveau) : `HttpURLConnection` comme `AndroidPlaylistLoader` (mêmes délais et redirections manuelles), sur `player_api.php` :
  - `get_live_categories`, `get_live_streams` ;
  - `get_vod_categories`, `get_vod_streams`, `get_vod_info` ;
  - `get_series_categories`, `get_series`, `get_series_info`.
  - Les identifiants passent en paramètres de requête (le protocole l'impose) ; ils n'apparaissent jamais dans un journal ni dans un message d'erreur.
- **Parsing JSON en flux** avec `android.util.JsonReader` (intégré, aucune dépendance), pour tenir des listes VOD de plusieurs dizaines de milliers d'entrées sans charger toute la réponse. Tolérance aux écarts entre fournisseurs : nombres livrés en chaînes, `null`, champs manquants, `category_id` absent, tableaux vides renvoyés comme `{}`.
- **Construction des URL de lecture au dernier moment**, sans les stocker : `{serveur}/live/{u}/{p}/{id}.{ext}`, `/movie/…/{id}.{ext}`, `/series/…/{episodeId}.{ext}`.
- **Erreurs en français** : identifiants refusés (`user_info.auth = 0`), compte expiré ou désactivé (`status`, `exp_date`), nombre maximal de connexions atteint, serveur injoignable, réponse non JSON.

### Lot 1 — réalisé (29 octobre 2026)

Livré dans `Domain.kt`, `Json.kt` (nouveau), `Xtream.kt` (nouveau) et `Sources.kt`, avec 33 tests unitaires. Trois décisions prises en cours de route :

- **Lecteur JSON maison** (`JsonStreamReader`) au lieu d'`android.util.JsonReader` : la classe du framework n'est qu'un stub dans les tests unitaires locaux (`isReturnDefaultValues`), ce qui aurait rendu les parseurs testables seulement sur émulateur. Même API en flux, aucune dépendance, nombres rendus en texte pour laisser le parseur Xtream choisir la coercition.
- **Référence de flux `xtream://{sourceId}/live/{stream_id}.{ext}`** dans `Channel.streamUrl` : les chaînes Xtream rejoignent la table `channels` (lot 2) sans qu'une URL contenant les identifiants y soit jamais écrite. `XtreamStreamRef.resolve(account)` construit l'URL complète au moment de la lecture (lot 3). Films et épisodes portent `sourceId`, identifiant fournisseur et extension, et font de même via `streamRef()`.
- **`XtreamClient` reçoit le compte et l'identifiant de source** à chaque appel ; `movieDetails` et `seriesDetails` complètent l'entrée de liste connue. Le lot 2 fera porter le compte par la source ; un faux client suffira aux tests d'interface du lot 4.

Également : `Http` partagé entre playlists et Xtream (`HttpStatusException` porte le code seul), `SourceKind.XTREAM` refusé par `AndroidPlaylistLoader` jusqu'au lot 2, `XtreamAccountInfo.blocker()` (expiré, désactivé) et `connectionsMessage()` (connexions saturées) pour le test de connexion du lot 3, `liveExtension` (`ts` sauf compte HLS seul).

## Lot 2 — Identifiants et persistance

- **Mot de passe chiffré** avec une clé AES-GCM de l'Android Keystore (non exportable) ; seul le texte chiffré est en base. Pas d'`EncryptedSharedPreferences`, dépréciée. Si la clé est perdue (restauration sur un autre appareil), la source passe en erreur « Identifiants à ressaisir ».
- Sauvegarde : `allowBackup="false"` est déjà en place. Sur API 31+, il faut ajouter `dataExtractionRules` pour exclure aussi le transfert d'appareil à appareil, puisque la clé Keystore ne suit pas.
- **Room v3 et migration 2 → 3** (la v2 vient du lot 0.2) : colonnes Xtream sur `sources` (serveur, identifiant, mot de passe chiffré, date d'expiration du compte) ; tables `movies`, `series`, `vod_categories` (films et séries séparés des catégories TV) ; `seasons` et `episodes` en cache, remplis à la première ouverture d'une série puis rafraîchis avec la source.
- Les chaînes en direct Xtream rejoignent la table `channels` existante : l'onglet TV, la recherche et les favoris fonctionnent sans changement. Identifiant stable : `xtream:{sourceId}:{stream_id}`.
- Favoris étendus aux films et aux séries (table `favorites` réutilisée, identifiants préfixés).
- **Reprise de lecture** : position par film et par épisode (table `progress`), mise à jour toutes les 10 s et à la sortie du lecteur.

### Lot 2 — réalisé (29 octobre 2026)

Livré dans `Secrets.kt` (nouveau), `Persistence.kt`, `Sources.kt`, `Domain.kt`, `LiveViewModel.kt`, le manifeste et `res/xml/data_extraction_rules.xml`, avec 9 tests unitaires et 7 instrumentés (6 Room + l'étape 2 → 3 de `MigrationTest`). Décisions prises en cours de route :

- **Chiffrement en deux couches** : `AesGcmCipher` (JCE pur, testable sur la JVM) reçoit la clé d'une fonction ; `KeystoreSecretCipher` la fournit depuis l'Android Keystore et ne la crée qu'au chiffrement. Le déchiffrement d'une clé absente rend `null`, d'où « Identifiants à ressaisir » sur la source, sans toucher au catalogue déjà importé.
- **Le mot de passe ne traverse que deux fonctions** : `addXtreamSource` (chiffrement) et `credentials()` du dépôt (déchiffrement pour appeler le fournisseur ou résoudre une URL via `playbackUrl`). Il n'est ni dans `Source`, ni dans `LiveState`, ni dans un `SavedStateHandle`.
- **Pagination unifiée** : `ChannelPagingSource` devient `KeysetPagingSource<E : Ordered>` et sert aussi `movies` et `series` (index `(sourceId, position)` et `(categoryId, sourceId, position)`, colonne `searchTitle` normalisée). Les compteurs VOD passent par une `@RawQuery` observée.
- **Caches** : `movies.detailsLoadedAt` et `series.detailsLoadedAt` marquent les fiches déjà complétées ; une actualisation de la source remplace les lignes et vide `seasons`/`episodes`, les fiches se rechargent à l'ouverture suivante. Fournisseur injoignable : le cache répond s'il existe.
- **`progress` survit aux actualisations** (les identifiants sont stables) et disparaît avec la source (`itemId LIKE 'xtream:{id}:%'`). La mise à jour toutes les 10 s relève du lecteur, lot 3.
- `favorites.channelId` garde son nom de colonne v1 mais accepte les identifiants de films et de séries ; la purge tient compte des trois tables.
- Interface : `addXtreamSource` existe dans le ViewModel (message « N chaînes, M films et S séries importés »), la fiche source affiche « Xtream · hôte » ; le formulaire arrive au lot 3.

## Lot 3 — Interface

- **Sources** : choix « Playlist M3U » ou « Fournisseur Xtream » ; formulaire serveur, identifiant, mot de passe masqué ; test de connexion avant l'enregistrement ; la fiche affiche l'hôte seul, l'expiration du compte et les compteurs (chaînes, films, séries).
- **Films** : catégories en filtres (comme l'onglet TV), grille d'affiches (Coil), filtre par titre, fiche détaillée (synopsis, année, durée, note) avec « Lire » ou « Reprendre à 42 min ».
- **Séries** : grille d'affiches, fiche avec sélecteur de saison et liste d'épisodes (miniature, durée, progression), « Épisode suivant » à la fin d'un épisode.
- **Lecteur** : le `Playback` Media3 existant est réutilisé, avec barre de progression et saut ±10 s pour la VOD ; plein écran et rotation inchangés.
- **Accueil** : rangées « Reprendre », « Films récents » et « Séries récentes » quand une source Xtream existe.
- **Recherche** : résultats groupés Chaînes, Films, Séries.
- `DemoContent.kt` est supprimé ; sans source Xtream, Films et Séries affichent un état vide qui mène à l'ajout d'une source.

### Lot 3 — réalisé (29 octobre 2026)

Livré dans `LiveViewModel.kt`, `Preview.kt`, `MainActivity.kt`, `SourcesScreen.kt`, `VodScreens.kt` (nouveau) ; `DemoContent.kt` supprimé. Décisions prises en cours de route :

- **Un seul lecteur, un seul type** : `PlayItem` (chaîne, film ou épisode) porte la référence de flux et la position de reprise ; `rememberPlayback(item, resolve, onProgress)` résout l'URL dans un `LaunchedEffect` au démarrage et à chaque « Réessayer », si bien que l'URL avec identifiants ne vit que dans ExoPlayer. `state.selected` (TV) et `state.vod` (film ou épisode) s'excluent ; changer d'onglet, de fiche ou ouvrir Sources arrête tout, comme au Sprint 2.
- **Fiches dans l'onglet** plutôt qu'en feuille modale : la fiche film ou série remplace la grille dans la même `LazyColumn`, Retour la ferme (et arrête la lecture), les tests Compose la parcourent par défilement. Le lecteur VOD s'affiche en tête de fiche ; « Épisode suivant » y figure et apparaît aussi en plein écran à la fin.
- **Grille d'affiches en rangées de trois** dans la `LazyColumn` paginée (une `LazyVerticalGrid` imbriquée n'est pas défilable) ; chaque rangée lit ses trois entrées dans `LazyPagingItems`.
- **Test de connexion** : `ChannelRepository.checkXtreamAccount` (statut, date de fin, connexions, formats) ; l'ajout refait la vérification puis importe, donc un compte expiré ou refusé n'est jamais enregistré.
- **Reprise** : le lecteur rapporte la position toutes les 10 s, à la fin (position = durée, donc « Vu ») et à la fermeture ; au-delà de 95 % plus de proposition de reprise. La rangée « Reprendre » retrouve film ou épisode et série depuis l'identifiant et rouvre la fiche à la bonne saison.
- **Icônes** : `material-icons-core` seulement, d'où un bouton texte « Afficher / Masquer » pour le mot de passe.

## Lot 4 — Tests, CI et documentation

- **Unitaires** : parseurs Xtream sur des réponses enregistrées et anonymisées (champs manquants, types incohérents, 30 000 films), construction des URL, messages d'erreur (aucun ne contient d'identifiant), chiffrement aller-retour, ViewModel Films et Séries avec un dépôt mémoire.
- **Instrumentés** : migration Room 2 → 3 (à la suite de 1 → 2) avec données réelles conservées ; Compose : ajout d'une source Xtream par un faux client, navigation Films → fiche → lecture, Séries → saison → épisode, reprise de lecture. Toujours aucun réseau.
- **CI** inchangée (émulateur Pixel 2 API 35), avec la vérification du schéma exporté.
- `README.md` et `VALIDATION.md` réécrits pour le Sprint 3 ; un commit par lot, comme au Sprint 2.

### Lot 4 — réalisé (29 octobre 2026)

`FormatTest` et `PlayerResolutionTest` ajoutés ; CI inchangée ; `README.md` et `VALIDATION.md` réécrits pour le Sprint 3. Totaux : 86 tests unitaires, 27 instrumentés, lint 28 avertissements. La validation sur appareil est consignée dans `VALIDATION.md`, « Validation sur appareil ».

## Bilan du sprint

| Lot | État | Commit |
|---|---|---|
| 0.1 schéma exporté | fait | `8a99470` |
| 0.2 pagination | fait, défilement mesuré sur appareil au 0.3 | `7836642` |
| 0.3 validation appareil | fait | `635ee4b` |
| 0.4 délai borné | fait | `430bc42` |
| 1 client Xtream | fait | `5c36b25` |
| 2 identifiants et Room v3 | fait | `6023f64` |
| 3 interface | fait | `b381fd8` |
| 4 tests et documentation | fait | dernier commit de la branche |

Validation sur le Xiaomi 15 faite le 29 octobre 2026 avec un compte Xtream de test : 14 scénarios sur 17 vérifiés par adb, 3 rapportés par l'utilisateur (actualisation, arrière-plan, mode avion), aucun défaut relevé. Reste : PR vers `main`.

## Ordre et dépendances

1. Lot 0.1 (schéma exporté) avant toute migration ; 0.2 à 0.4 en parallèle des lots suivants.
2. Lot 1 (client et parseurs, testables seuls), puis Lot 2 (persistance), puis Lot 3 (interface).
3. Lot 4 en continu ; validation sur appareil avec le compte de test à la fin.

## Hors périmètre

Guide des programmes (EPG / XMLTV), rattrapage (catch-up / timeshift), sous-titres externes, plusieurs profils, contrôle parental, Chromecast, téléchargement hors ligne, Android TV et tablettes.

## Risques

- **Hétérogénéité des fournisseurs** : le format Xtream n'est pas normalisé. Mitigation : parseurs tolérants et jeux de réponses variés en test.
- **Limite de connexions simultanées** : l'aperçu TV et le lecteur ne doivent jamais ouvrir deux flux en même temps (le lecteur unique du Sprint 2 le garantit déjà ; à conserver).
- **Volume** : catalogues VOD de plusieurs dizaines de milliers d'entrées. C'est pour cela que le Lot 0.2 (chargement par catégorie ou paginé) passe avant l'interface Films.
- **Licéité** : comme pour les playlists, l'application ne fournit ni ne suggère aucun fournisseur ; la responsabilité des sources reste à l'utilisateur.
