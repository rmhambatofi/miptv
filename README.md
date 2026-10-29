# miptv — Sprint 2 Android smartphone

Application native Kotlin pour **smartphones Android uniquement** (API 26+), Jetpack Compose et Material 3. Aucun lanceur Leanback, composant Android TV ni navigation D-pad. L'application nécessite un écran tactile ; les tablettes ne sont pas une cible de conception ou de validation.

Le Sprint 2 remplace le catalogue de démonstration par de **vraies playlists IPTV M3U/M3U8** fournies par l'utilisateur : ajout de sources, importation en flux, catalogue Live TV avec logos, lecture Media3 plein écran, favoris et persistance locale.

## Fonctionnalités

- **Sources** (icône engrenage, ou bouton « Sources » de l'Accueil) : ajout d'une playlist par adresse `http(s)` ou depuis un fichier local `.m3u` / `.m3u8` via le sélecteur de documents (SAF, permission persistée). Nom, adresse ou URI, date de dernière actualisation, nombre de chaînes et dernière erreur sont enregistrés. Actualisation et suppression par source. Les adresses sont affichées hôte seul ; aucune adresse ni identifiant n'est journalisé.
- **Importation M3U** : `#EXTINF` (`tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, titre), `#EXTGRP`, `#EXTVLCOPT` (`http-user-agent`, `http-referrer` transmis au lecteur). Les groupes deviennent des catégories ; les lignes vides, le BOM, les retours chariot Windows, l'absence d'en-tête `#EXTM3U` et les attributs manquants sont tolérés. Parsing ligne à ligne sur `Dispatchers.IO`, sans charger la playlist en mémoire sous forme de chaîne.
- **TV** : pseudo-catégories « Toutes » et « Favoris », puis les catégories importées ; chaînes avec logos (Coil) ; filtre par nom appliqué à la catégorie ; état « Aucune source configurée » qui mène à l'écran d'ajout.
- **Lecture** : Media3/ExoPlayer (HLS, MP4, TS…) avec commandes tactiles, chargement, message d'erreur explicite en français (réseau, code HTTP, 404, format, décodeur) et bouton « Réessayer », y compris quand un flux reste plus de 20 s en chargement alors que la lecture est demandée ; reprise automatique en cas de décalage du direct. **Plein écran** en paysage avec barres système masquées, sortie par Retour ou croix. Le lecteur est créé par chaîne sélectionnée, libéré au changement de chaîne, de catégorie ou d'onglet, et mis en pause en arrière-plan (reprise manuelle au retour). Le passage en plein écran ou la rotation réutilisent le même lecteur.
- **Favoris** : étoile sur chaque fiche et dans l'aperçu ; catégorie « Favoris ». Les favoris survivent à l'actualisation d'une source grâce à des identifiants stables (`tvg-id`, sinon groupe + nom).
- **Persistance** : sources, catégories, chaînes et favoris sont conservés dans une base Room ; le catalogue est disponible au redémarrage sans réimport. Les listes sont paginées depuis la base : une playlist de 50 000 chaînes n'est jamais chargée en mémoire (voir `VALIDATION.md`, Sprint 3, lot 0.2).
- **Filtre et recherche** insensibles à la casse et aux accents (« chaine » trouve « CHAÎNE »), exécutés en SQL sur une colonne normalisée.
- Accueil (résumé du catalogue, dix premières chaînes), Recherche locale sur les chaînes importées, Films et Séries toujours fictifs (fiches sans lecture, en attente d'un fournisseur Xtream).
- Thème sombre, accent rose, prise en compte des barres système et du clavier.

## Démarrer

Installer JDK 17 ou 21, Android SDK 35 et Build Tools 35.0.0. Définir `ANDROID_HOME` ou `sdk.dir` dans `local.properties` (ignoré). Le wrapper Gradle est versionné avec contrôle SHA-256 de la distribution.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
# Sur un émulateur smartphone ou un téléphone connecté :
./gradlew connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.miptv/.MainActivity
```

Windows : `gradlew.bat`. Pas de compte, clé, backend ni configuration fournisseur : l'utilisateur fournit sa propre playlist. Aucune playlist, aucun flux ni aucun contenu n'est livré avec l'application ; la licéité des sources ajoutées relève de l'utilisateur.

## Architecture et versions

Un module `app` et un MVVM pragmatique, sans Hilt, Retrofit ni OkHttp direct :

- `Domain.kt` : modèles (`Category`, `Channel`, `Source`, `ImportResult`), `ChannelQuery` (catégorie + filtre) et `searchKey` (forme normalisée des noms), contrat asynchrone `ChannelRepository` (catégories, identifiants des favoris et sources en flux ; listes paginées `channels(query)` et compteurs `count(query)` ; lecture d'une chaîne ; ajout/actualisation/suppression de source, favoris), `M3uAdapter` ; modèles VOD du Sprint 3 (`Movie`, `Series`, `Season`, `Episode`, `SeriesInfo`), compte Xtream (`XtreamAccount`, `XtreamAccountInfo` avec `blocker()` : compte expiré ou désactivé, connexions saturées) et contrat `XtreamClient`.
- `M3uParser.kt` : implémentation de `M3uAdapter`, parsing en flux depuis un `Reader`/`InputStream`, lecture des attributs sans expression régulière.
- `Json.kt` : `JsonStreamReader`, lecteur JSON en flux (sous-ensemble de l'API `android.util.JsonReader`, qui n'est qu'un stub dans les tests unitaires locaux) et accesseurs tolérants (`nextLongOrNull`, `forEachElement`…) pour les types incohérents des panels Xtream.
- `Xtream.kt` : client Xtream Codes (lot 1 du Sprint 3). `XtreamParser` lit les réponses de `player_api.php` (compte, catégories, chaînes, films, fiche film, séries, fiche série avec saisons et épisodes) ; `HttpXtreamClient` les télécharge via `Http` ; `XtreamIds` fixe les identifiants `xtream:{source}:…` ; `XtreamStreamRef` remplace l'URL de lecture par `xtream://{source}/live/{id}.{ext}` et la résout avec les identifiants seulement au moment de lire ; `describeXtreamError` traduit les échecs en français sans jamais reproduire serveur, identifiant ou mot de passe. Aucune source Xtream n'est encore enregistrable : persistance au lot 2, interface au lot 3.
- `Sources.kt` : `Http` (`HttpURLConnection` avec délais et redirections manuelles, partagé par les playlists et le client Xtream), `PlaylistLoader` (`AndroidPlaylistLoader` : `Http` pour les adresses, `ContentResolver` pour les URI SAF), traduction des erreurs en messages français sans écho de l'adresse, et `MemoryChannelRepository` (implémentation mémoire, référence et support de tests).
- `Secrets.kt` : `SecretCipher` ; `AesGcmCipher` (AES-256-GCM, nonce aléatoire par valeur, sortie `v1:` + base64) et `KeystoreSecretCipher`, dont la clé vit dans l'Android Keystore, non exportable, créée au premier chiffrement. Un déchiffrement impossible (clé perdue après restauration sur un autre appareil) rend `null`, jamais une valeur fausse, et la source passe en « Identifiants à ressaisir ».
- `Persistence.kt` : entités, DAO et base Room (`miptv.db`, version 3, migrations `MIGRATION_1_2` et `MIGRATION_2_3`), `KeysetPagingSource` (pagination par clé `(sourceId, position)` partagée par les chaînes, les films et les séries), `RoomChannelRepository` : sources M3U et Xtream (mot de passe chiffré en base, déchiffré seulement pour appeler le fournisseur ou construire une URL de lecture), catalogue VOD, cache des saisons et épisodes, favoris et positions de reprise.
- `LiveViewModel.kt` : état `StateFlow` (`LiveState` : catégories, favoris, compteurs, requêtes courantes), listes paginées `channels`/`searchResults`/`homeChannels` mises en cache dans le ViewModel, validation de la catégorie à chaque changement de catégories et relecture de la chaîne en lecture à chaque import ou suppression, statut d'importation, plein écran, `SavedStateHandle` (onglet, catégorie, recherche, filtre, écran Sources).
- `MainActivity.kt` : Compose Material 3, cinq onglets, écran Sources, hissage du lecteur (`rememberPlayback`) au-dessus des deux mises en page, effet plein écran (orientation et barres système).
- `Preview.kt` : lecteur Media3 (`Playback`, `PlayerSurface`, `Preview`, `FullscreenPlayer`), construction de la source de données HTTP (`User-Agent`, `Referer`), délai de chargement borné (`LoadingWatch`), messages d'erreur.
- `SourcesScreen.kt` : formulaire d'ajout, sélecteur SAF, liste des sources.
- `MiptvApplication.kt` : câblage manuel (base Room + chargeur + dépôt).

### Choix de persistance : Room

Room a été préféré à DataStore parce que le catalogue peut compter des dizaines de milliers de chaînes : l'écriture est transactionnelle et par lots (remplacement des chaînes d'une seule source sans toucher aux autres), les favoris sont une table séparée reliée par identifiant stable, et les catégories sont partagées entre sources puis purgées lorsqu'elles deviennent orphelines. DataStore aurait imposé de sérialiser et de réécrire tout le catalogue à chaque changement de favori. Le coût est le plugin KSP (`2.0.21-1.0.28`, aligné sur Kotlin 2.0.21) et `room-compiler`. Le schéma est exporté par le plugin Gradle Room dans `app/schemas` (versionné) et embarqué dans l'APK de test : `MigrationTest` crée une base v1 remplie à partir de ce fichier puis la migre et la valide version par version. La CI échoue si le schéma généré diffère de celui du dépôt.

### Listes paginées

Les listes (TV, Recherche, Accueil) passent par Paging 3 (`paging-runtime`, `paging-compose`). La source Room fournie par `room-paging` a été écartée : elle compte toute la requête à chaque chargement et lit les pages par `LIMIT/OFFSET`, dont le coût croît avec la profondeur. `ChannelPagingSource` reprend chaque page après la dernière ligne lue, `(sourceId, position) > (?, ?)`, sur les index `(sourceId, position)` et `(categoryId, sourceId, position)`, et recharge autour de la chaîne affichée après une invalidation. Les favoris sont un ensemble d'identifiants séparé : cocher une étoile n'invalide que la liste et le compteur « Favoris ». SQLite ne repliant la casse qu'en ASCII, le filtre porte sur la colonne `searchName` (minuscules, sans accents), remplie à l'import et par la migration v1 → v2.

### Versions et manifeste

AGP 8.13.2, Gradle 8.13, Kotlin/plugin Compose/KSP 2.0.21, Compose BOM 2024.12.01, Lifecycle 2.8.7, Media3 1.5.1, Room 2.6.1, Paging 3.3.6, Coil 2.7.0, coroutines 1.9.0 sont épinglés. Compile/target SDK 35, min SDK 26, bytecode Java 17.

Le manifeste autorise désormais le trafic en clair (`usesCleartextTraffic="true"`) car la grande majorité des playlists et des flux IPTV sont servis en `http://`. L'activité déclare `configChanges` pour que la rotation et le passage en paysage du plein écran n'entraînent pas de recréation (le lecteur survit).

### Sécurité et confidentialité

Les adresses de playlist peuvent contenir des identifiants : elles ne sont jamais journalisées, les messages d'erreur ne les reproduisent pas (seul le code HTTP est affiché) et l'interface n'affiche que le schéma et l'hôte. Les URI de fichiers reçoivent une permission de lecture persistée ; si elle est révoquée, l'actualisation signale « Fichier inaccessible ». Aucun identifiant fournisseur en dur, aucun backend, aucune télémétrie.

Les comptes Xtream : le mot de passe n'est jamais en clair en base (AES-GCM, clé Keystore), n'apparaît ni dans le modèle `Source`, ni dans l'état du ViewModel, ni dans un message ; les chaînes stockent une référence `xtream://` et l'URL complète n'est construite qu'au moment de lire (`playbackUrl`). `allowBackup="false"` et `dataExtractionRules` (API 31+) excluent toutes les données de la sauvegarde cloud et du transfert d'appareil à appareil, puisque la clé Keystore ne suivrait pas.

## Tests et vérification

74 tests unitaires : parseur M3U (attributs, valeurs manquantes, lignes vides, BOM, `#EXTVLCOPT`, retours chariot Windows, titres avec virgules, identifiants stables, fusion des groupes, playlist de 25 000 chaînes, lecture des attributs identique à l'ancienne expression régulière) ; ViewModel (catalogue, catégories, sélection, recherche, filtre insensible aux accents, compteurs, restauration sans autoplay, ajout/actualisation/suppression de source, erreurs, plein écran, favoris) ; messages d'erreur de lecture ; lecteur JSON (jetons, échappements Unicode, chaînes plus longues que le tampon, documents tronqués ou HTML, accesseurs tolérants) ; parseurs Xtream sur des réponses enregistrées et anonymisées (compte actif, illimité, refusé, expiré, banni, saturé ; catégories en tableau, `{}` ou indexées par identifiant ; chaînes sans catégorie, doublons, nombres en chaînes ; films aux champs manquants et 30 000 films en moins d'une seconde ; fiche film ; séries et fiche série avec épisodes indexés par saison ou en tableaux imbriqués) ; normalisation du serveur, construction des URL de lecture et encodage des identifiants ; messages d'erreur sans aucun identifiant ; `HttpXtreamClient` face à un serveur local sur `ServerSocket` (paramètres de requête, redirection, HTML, HTTP 503, hôte injoignable) ; chiffrement AES-GCM aller-retour, texte altéré ou clé perdue ; dépôt mémoire avec un faux client Xtream (import, refus, expiration, identifiants perdus, cache des fiches, favoris et reprise) ; ViewModel ajoutant une source Xtream. Les tests ViewModel utilisent `MemoryChannelRepository` avec un chargeur factice et un `StandardTestDispatcher`, et lisent les listes paginées avec `paging-testing`.

Tests instrumentés : six tests Compose (cinq onglets, filtrage par catégorie et recherche, écran Sources avec adresse invalide puis valide, état sans source, aperçu et plein écran, favoris) sept tests Room en base mémoire (persistance et réouverture, favoris conservés à l'actualisation et échec conservant les chaînes, suppression d'une source, échec d'ajout, filtre SQL insensible à la casse et aux accents avec `%` et `_` littéraux, pagination par clé couvrant chaque chaîne une fois et rechargement au milieu, étoile n'invalidant que la liste « Favoris »), six tests Room sur les sources Xtream (`XtreamRepositoryTest` : import complet, mot de passe chiffré au repos et URL de lecture, clé perdue, compte refusé ou expiré, cache des fiches jusqu'à l'actualisation, favoris et reprise jusqu'à la suppression), un test de migration v1 → v2 → v3 (`MigrationTest`) et une mesure sur 50 000 chaînes (`CatalogPerformanceTest`, exécutée seulement avec `-e perf true`). Aucun test ne dépend du réseau.

GitHub Actions (`.github/workflows/android.yml`) compile l'APK et l'APK de test, exécute tests unitaires/lint et lance les tests instrumentés sur émulateur **Pixel 2 API 35**. Aucun déploiement ni fusion automatique.

Validation manuelle recommandée sur smartphone : import d'une playlist réelle de plusieurs milliers de chaînes, logos, lecture HLS et TS, plein écran et rotation, coupure réseau et nouvel essai, arrière-plan/reprise, redémarrage sans réimport, révocation de la permission d'un fichier. Voir `VALIDATION.md` pour les résultats effectifs de cette livraison.

## Historique

Sprint 1 (`main` jusqu'à la PR #3) : portage smartphone, cinq onglets, lecteur Media3 et catalogue de démonstration Blender. Sprint 2 (branche `feature/sprint2-live-tv`) : un commit par lot (parseur, sources, catalogue, lecture, persistance) puis documentation.

Références d'implémentation : [barre de navigation Compose](https://developer.android.com/develop/ui/compose/components/navigation-bar), [cycle de vie ExoPlayer](https://developer.android.com/media/media3/exoplayer/hello-world), [Room](https://developer.android.com/training/data-storage/room), [Coil Compose](https://coil-kt.github.io/coil/compose/), [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider).
