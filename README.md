# miptv — Sprint 2 Android smartphone

Application native Kotlin pour **smartphones Android uniquement** (API 26+), Jetpack Compose et Material 3. Aucun lanceur Leanback, composant Android TV ni navigation D-pad. L'application nécessite un écran tactile ; les tablettes ne sont pas une cible de conception ou de validation.

Le Sprint 2 remplace le catalogue de démonstration par de **vraies playlists IPTV M3U/M3U8** fournies par l'utilisateur : ajout de sources, importation en flux, catalogue Live TV avec logos, lecture Media3 plein écran, favoris et persistance locale.

## Fonctionnalités

- **Sources** (icône engrenage, ou bouton « Sources » de l'Accueil) : ajout d'une playlist par adresse `http(s)` ou depuis un fichier local `.m3u` / `.m3u8` via le sélecteur de documents (SAF, permission persistée). Nom, adresse ou URI, date de dernière actualisation, nombre de chaînes et dernière erreur sont enregistrés. Actualisation et suppression par source. Les adresses sont affichées hôte seul ; aucune adresse ni identifiant n'est journalisé.
- **Importation M3U** : `#EXTINF` (`tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, titre), `#EXTGRP`, `#EXTVLCOPT` (`http-user-agent`, `http-referrer` transmis au lecteur). Les groupes deviennent des catégories ; les lignes vides, le BOM, les retours chariot Windows, l'absence d'en-tête `#EXTM3U` et les attributs manquants sont tolérés. Parsing ligne à ligne sur `Dispatchers.IO`, sans charger la playlist en mémoire sous forme de chaîne.
- **TV** : pseudo-catégories « Toutes » et « Favoris », puis les catégories importées ; chaînes avec logos (Coil) ; filtre par nom appliqué à la catégorie ; état « Aucune source configurée » qui mène à l'écran d'ajout.
- **Lecture** : Media3/ExoPlayer (HLS, MP4, TS…) avec commandes tactiles, chargement, message d'erreur explicite en français (réseau, code HTTP, 404, format, décodeur) et bouton « Réessayer » ; reprise automatique en cas de décalage du direct. **Plein écran** en paysage avec barres système masquées, sortie par Retour ou croix. Le lecteur est créé par chaîne sélectionnée, libéré au changement de chaîne, de catégorie ou d'onglet, et mis en pause en arrière-plan (reprise manuelle au retour). Le passage en plein écran ou la rotation réutilisent le même lecteur.
- **Favoris** : étoile sur chaque fiche et dans l'aperçu ; catégorie « Favoris ». Les favoris survivent à l'actualisation d'une source grâce à des identifiants stables (`tvg-id`, sinon groupe + nom).
- **Persistance** : sources, catégories, chaînes et favoris sont conservés dans une base Room ; le catalogue est disponible au redémarrage sans réimport.
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

- `Domain.kt` : modèles (`Category`, `Channel`, `Catalog`, `Source`, `ImportResult`), contrat asynchrone `ChannelRepository` (flux `catalog` et `sources`, ajout/actualisation/suppression de source, favoris), `M3uAdapter` (implémenté) et `XtreamAdapter` (point d'extension non implémenté).
- `M3uParser.kt` : implémentation de `M3uAdapter`, parsing en flux depuis un `Reader`/`InputStream`.
- `Sources.kt` : `PlaylistLoader` (`AndroidPlaylistLoader` : `HttpURLConnection` avec délais et redirections manuelles, `ContentResolver` pour les URI SAF), traduction des erreurs en messages français sans écho de l'adresse, et `MemoryChannelRepository` (implémentation mémoire, référence et support de tests).
- `Persistence.kt` : entités, DAO et base Room (`miptv.db`, version 1), `RoomChannelRepository`.
- `LiveViewModel.kt` : état `StateFlow` (`LiveState`), collecte des flux du dépôt, validation de la catégorie et de la sélection à chaque changement de catalogue, statut d'importation, plein écran, `SavedStateHandle` (onglet, catégorie, recherche, filtre, écran Sources).
- `MainActivity.kt` : Compose Material 3, cinq onglets, écran Sources, hissage du lecteur (`rememberPlayback`) au-dessus des deux mises en page, effet plein écran (orientation et barres système).
- `Preview.kt` : lecteur Media3 (`Playback`, `PlayerSurface`, `Preview`, `FullscreenPlayer`), construction de la source de données HTTP (`User-Agent`, `Referer`), messages d'erreur.
- `SourcesScreen.kt` : formulaire d'ajout, sélecteur SAF, liste des sources.
- `MiptvApplication.kt` : câblage manuel (base Room + chargeur + dépôt).

### Choix de persistance : Room

Room a été préféré à DataStore parce que le catalogue peut compter des dizaines de milliers de chaînes : l'écriture est transactionnelle et par lots (remplacement des chaînes d'une seule source sans toucher aux autres), les favoris sont une table séparée reliée par identifiant stable, et les catégories sont partagées entre sources puis purgées lorsqu'elles deviennent orphelines. DataStore aurait imposé de sérialiser et de réécrire tout le catalogue à chaque changement de favori. Le coût est le plugin KSP (`2.0.21-1.0.28`, aligné sur Kotlin 2.0.21) et `room-compiler`. Le schéma est exporté par le plugin Gradle Room dans `app/schemas` (versionné) et embarqué dans l'APK de test : `MigrationTest` ouvre une base v1 remplie à partir de ce fichier, et chaque version future y ajoutera sa migration. La CI échoue si le schéma généré diffère de celui du dépôt.

### Versions et manifeste

AGP 8.13.2, Gradle 8.13, Kotlin/plugin Compose/KSP 2.0.21, Compose BOM 2024.12.01, Lifecycle 2.8.7, Media3 1.5.1, Room 2.6.1, Coil 2.7.0, coroutines 1.9.0 sont épinglés. Compile/target SDK 35, min SDK 26, bytecode Java 17.

Le manifeste autorise désormais le trafic en clair (`usesCleartextTraffic="true"`) car la grande majorité des playlists et des flux IPTV sont servis en `http://`. L'activité déclare `configChanges` pour que la rotation et le passage en paysage du plein écran n'entraînent pas de recréation (le lecteur survit).

### Sécurité et confidentialité

Les adresses de playlist peuvent contenir des identifiants : elles ne sont jamais journalisées, les messages d'erreur ne les reproduisent pas (seul le code HTTP est affiché) et l'interface n'affiche que le schéma et l'hôte. Les URI de fichiers reçoivent une permission de lecture persistée ; si elle est révoquée, l'actualisation signale « Fichier inaccessible ». Aucun identifiant fournisseur en dur, aucun backend, aucune télémétrie.

## Tests et vérification

28 tests unitaires : parseur M3U (attributs, valeurs manquantes, lignes vides, BOM, `#EXTVLCOPT`, retours chariot Windows, titres avec virgules, identifiants stables, fusion des groupes, playlist de 25 000 chaînes) ; ViewModel (catalogue, catégories, sélection, recherche, filtre, restauration sans autoplay, ajout/actualisation/suppression de source, erreurs, plein écran, favoris) ; messages d'erreur de lecture. Les tests ViewModel utilisent `MemoryChannelRepository` avec un chargeur factice et un `StandardTestDispatcher`.

Tests instrumentés : six tests Compose (cinq onglets, filtrage par catégorie et recherche, écran Sources avec adresse invalide puis valide, état sans source, aperçu et plein écran, favoris) et quatre tests Room en base mémoire (persistance et réouverture, favoris conservés à l'actualisation et échec conservant les chaînes, suppression d'une source, échec d'ajout), ainsi qu'un test de migration (`MigrationTest`, base v1 remplie depuis le schéma exporté). Aucun test ne dépend du réseau.

GitHub Actions (`.github/workflows/android.yml`) compile l'APK et l'APK de test, exécute tests unitaires/lint et lance les tests instrumentés sur émulateur **Pixel 2 API 35**. Aucun déploiement ni fusion automatique.

Validation manuelle recommandée sur smartphone : import d'une playlist réelle de plusieurs milliers de chaînes, logos, lecture HLS et TS, plein écran et rotation, coupure réseau et nouvel essai, arrière-plan/reprise, redémarrage sans réimport, révocation de la permission d'un fichier. Voir `VALIDATION.md` pour les résultats effectifs de cette livraison.

## Historique

Sprint 1 (`main` jusqu'à la PR #3) : portage smartphone, cinq onglets, lecteur Media3 et catalogue de démonstration Blender. Sprint 2 (branche `feature/sprint2-live-tv`) : un commit par lot (parseur, sources, catalogue, lecture, persistance) puis documentation.

Références d'implémentation : [barre de navigation Compose](https://developer.android.com/develop/ui/compose/components/navigation-bar), [cycle de vie ExoPlayer](https://developer.android.com/media/media3/exoplayer/hello-world), [Room](https://developer.android.com/training/data-storage/room), [Coil Compose](https://coil-kt.github.io/coil/compose/), [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider).
