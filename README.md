# miptv

Lecteur IPTV natif pour **smartphones Android** (API 26+), écrit en Kotlin avec Jetpack Compose et Material 3. L'utilisateur y ajoute ses propres sources (playlists M3U/M3U8 ou compte Xtream Codes) et retrouve dans une seule application la TV en direct, les films, les séries, la reprise de lecture, les favoris et le guide des programmes.

L'application ne livre aucun contenu : aucune playlist, aucun compte, aucun flux ni aucun guide n'est inclus, aucun fournisseur n'est suggéré, et la licéité des sources ajoutées relève de l'utilisateur. Elle n'a ni compte, ni backend, ni télémétrie. Elle cible les téléphones à écran tactile : pas de lanceur Leanback, de composant Android TV ni de navigation D-pad, et les tablettes ne sont ni conçues ni validées.

Version 0.5.0 (`versionCode` 5).

## Sommaire

- [Utilisation](#utilisation)
- [Limites connues](#limites-connues)
- [Compiler et installer](#compiler-et-installer)
- [Architecture](#architecture)
- [Choix techniques](#choix-techniques)
- [Sécurité et confidentialité](#sécurité-et-confidentialité)
- [Tests et intégration continue](#tests-et-intégration-continue)
- [Documents du dépôt](#documents-du-dépôt)

## Utilisation

### Navigation

Cinq onglets en bas de l'écran : **Accueil**, **TV**, **Films**, **Séries** et **Recherche**. Les sources se gèrent depuis l'icône engrenage (ou le bouton « Sources » de l'Accueil). Retour ferme d'abord le plein écran, le guide, une fiche ou l'écran Sources, puis remonte les niveaux de l'onglet TV et revient enfin à l'Accueil. Au premier lancement, un écran d'ouverture d'environ 2 s (logo, égaliseur, son synthétisé) précède l'Accueil.

Le thème est sombre (fond `#0B0D12`, surface `#181C26`, accent `#FF647C`), avec une barre d'onglets à pastille et des icônes vectorielles maison. L'interface tient compte des barres système et du clavier.

### Ajouter une source

L'écran Sources propose deux formulaires.

- **Playlist M3U** : une adresse `http(s)` ou un fichier local `.m3u` / `.m3u8` choisi dans le sélecteur de documents (la permission de lecture est conservée). Un champ facultatif « Guide des programmes (XMLTV) » accepte un fichier `.xml` ou `.xml.gz` ; sinon, l'adresse du guide vient de l'en-tête de la playlist (`url-tvg`, `x-tvg-url` ou `tvg-url`).
- **Fournisseur Xtream** : serveur, identifiant et mot de passe (masqué). « Tester la connexion » décrit le compte : statut, date de fin, connexions autorisées, formats. L'ajout vérifie d'abord le compte (expiré, désactivé ou connexions saturées sont refusés avec un message), puis importe chaînes, films et séries.

Chaque source a sa fiche : hôte seul (jamais l'adresse complète), identifiant, expiration du compte, nombre de chaînes, films et séries, dernière actualisation, dernière erreur et état du guide (nombre de programmes, date, erreur). Elle s'actualise ou se supprime individuellement. Une source dont le mot de passe ne peut plus être déchiffré (par exemple après une restauration sur un autre appareil) affiche « Identifiants à ressaisir » ; son catalogue reste consultable.

Ce que lit l'importation M3U :

- les attributs `#EXTINF` : `tvg-id`, `tvg-name`, `tvg-logo`, `group-title` et le titre ;
- les lignes `#EXTGRP` ;
- les options `#EXTVLCOPT` : `http-user-agent` et `http-referrer` sont transmis au lecteur.

Les groupes deviennent des catégories. Les lignes vides, le BOM, les retours chariot Windows, l'absence d'en-tête `#EXTM3U` et les attributs manquants sont tolérés.

### Accueil

- En haut, les films récents en héros défilant (« Lecture », « Détails ») ; sans fournisseur Xtream, un résumé du catalogue avec « Explorer la TV » et « Sources ».
- « Continuer le visionnage » : films et épisodes inachevés, temps restant, barre de progression, reprise en un toucher depuis la fiche.
- « Films récents » et « Séries récentes ».
- « Chaînes favorites », avec le programme en cours : un toucher lit la chaîne, un appui long ouvre son guide, « Toutes » ouvre la catégorie Favoris.
- Dix chaînes « À découvrir ».

### TV

L'onglet a trois niveaux :

1. **Les catégories** : « Toutes », « Favoris », puis les catégories importées (le préfixe de zone `FR |` est affiché à part), en liste ou en couverture.
2. **Les chaînes d'une catégorie** : en liste ou en grille de logos (ou le nom court de la chaîne quand il n'y a pas de logo), avec un filtre par nom derrière la loupe. Sous chaque chaîne s'affiche le programme en cours avec sa progression et « À suivre 20:35 · Météo », quand le guide le connaît.
3. **Le lecteur** en haut, avec les chaînes de la catégorie qui défilent dessous (« En cours » ; « Toutes » revient à la liste).

Sans source, l'onglet affiche « Aucune source configurée » et mène à l'ajout.

### Films et Séries

Les deux onglets ont la même structure : héros défilant des nouveautés, pilule « Catégories » qui ouvre les catégories du fournisseur, filtre par titre derrière la loupe et grille de jaquettes sur trois colonnes. Sans fournisseur Xtream, ils mènent à l'ajout d'une source.

- **Fiche film** : jaquette plein cadre, catégorie, année, durée, note et synopsis (chargé une fois, puis gardé en cache). Les boutons sont « Regarder maintenant », ou « Reprendre à 42 min » et « Recommencer » quand une position existe, puis « Ma liste » et « Partager ». Le partage envoie le titre seul, jamais d'adresse. Le lecteur prend la place de la jaquette.
- **Fiche série** : « Reprendre S1 · E3 » (dernier épisode inachevé) ou « Regarder S1 · E1 », « Ma liste », « Partager », puces de saison et liste d'épisodes (miniature, durée, « Vu » ou position, barre de progression). « Épisode suivant » enchaîne en cours de lecture ou à la fin, y compris en plein écran. Saisons et épisodes sont chargés à la première ouverture, puis servis du cache jusqu'à l'actualisation de la source.

### Lecture

Un seul lecteur Media3/ExoPlayer sert la TV, les films et les épisodes : HLS, MP4, MKV, TS et les autres formats d'ExoPlayer.

- **TV** : commandes propres, dans le style de YouTube : réduire, « DIRECT », pause, son, guide et plein écran. Elles se masquent après 5 s de lecture. Le plein écran ajoute chaîne précédente et suivante. Un décalage du direct est rattrapé automatiquement.
- **Films et épisodes** : contrôleur Media3 avec barre de temps et sauts de ±10 s. La position est sauvegardée toutes les 10 s, à la fin et à la fermeture ; au-delà de 95 %, le titre est « Vu ».
- **Plein écran** en paysage, barres système masquées ; sortie par Retour ou par la croix. La rotation et le passage en plein écran réutilisent le même lecteur.
- **Erreurs** : un message explicite en français (réseau, code HTTP, 404, format, décodeur) avec « Réessayer », y compris quand un flux reste plus de 20 s en chargement.

Le lecteur est libéré au changement de sélection, de catégorie, d'onglet ou de fiche, et mis en pause quand l'application passe en arrière-plan (la reprise est manuelle).

### Guide des programmes

Le programme en cours et le suivant s'affichent sous chaque chaîne : dans TV, l'Accueil, la Recherche, l'aperçu et le plein écran. Un bouton calendrier, sur la chaîne comme dans le lecteur, ouvre le **guide de la chaîne** :

- « Lire », ou l'aperçu si la chaîne joue déjà ;
- des puces Aujourd'hui, Demain puis les dates suivantes ;
- les programmes du jour avec leurs heures, le numéro d'épisode, la catégorie et une description dépliable ; le programme en cours est surligné et affiché en premier.

Les jours au-delà du dernier programme connu sont estompés (annoncés « Sans programme » aux lecteurs d'écran). Un tel jour affiche « Pas encore de programmes pour ce jour : le guide connu s'arrête demain à 15:20. »

D'où vient le guide :

- **Playlist M3U** : le fichier XMLTV désigné par l'en-tête de la playlist ou par le champ de la fiche source. « Actualiser le guide » le recharge.
- **Fournisseur Xtream** : le guide complet du fournisseur (`xmltv.php`) est chargé pour les **chaînes favorites** uniquement. Mettre une chaîne en favori recharge le guide de sa source 3 s après la dernière étoile, sans passer par Sources ; le résultat s'affiche sur la fiche source. Les autres chaînes reçoivent leurs prochains programmes à la demande (`get_short_epg`) quand on les sélectionne ou qu'on ouvre leur guide. La feuille guide d'une chaîne hors favoris annonce alors un « Guide limité aux prochaines heures », avec un bouton « Ajouter aux favoris ».

Le guide est réimporté avec sa source. Il est aussi rechargé automatiquement, sur réseau non facturé, une fois par jour quand il a plus de 20 h, et au lancement de l'application quand il a plus de 24 h. Il couvre de 6 h avant à 3 jours après l'import ; un programme est purgé 12 h après sa fin.

### Recherche

La recherche est locale et insensible à la casse et aux accents (« chaine » trouve « CHAÎNE »). Les résultats sont groupés en Chaînes, Films, Séries et, dès qu'une source a un guide, Programmes (en cours ou à venir), avec leurs compteurs. Un résultat ouvre la chaîne dans TV, la fiche dans Films ou Séries, ou le guide de la chaîne au jour du programme.

### Favoris et persistance

Un signet sur chaque chaîne et « Ma liste » sur chaque fiche ajoutent aux favoris, qui ont leur catégorie « Favoris » dans les trois onglets. Ils survivent à l'actualisation d'une source grâce à des identifiants stables : `tvg-id`, sinon groupe + nom pour une playlist ; `xtream:{source}:…` pour un fournisseur.

Sources, catégories, chaînes, favoris, catalogue VOD, positions de lecture et programmes sont conservés sur le téléphone : tout est disponible au redémarrage sans réimport. Les listes sont lues page par page depuis la base, si bien qu'une playlist de 50 000 chaînes n'est jamais chargée en mémoire.

## Limites connues

- **Guide Xtream hors favoris** : seules les chaînes favorites reçoivent le guide complet du fournisseur. Les autres n'ont que les quelques programmes de `get_short_epg`. Certains fournisseurs ne publient d'ailleurs qu'environ 25 h de programmes, même dans le guide complet.
- **Téléphones Xiaomi (HyperOS)** : sans l'autorisation « Démarrage automatique », HyperOS retire l'actualisation quotidienne du guide quand l'application passe en arrière-plan. Le guide se recharge alors surtout au lancement (plus de 24 h).
- **Pas encore pris en charge** : rattrapage (catch-up), rappels, grille multi-chaînes, images de programmes, Android TV et tablettes.

## Compiler et installer

Prérequis : JDK 17 ou 21, Android SDK 35 et Build Tools 35.0.0. Définir `ANDROID_HOME`, ou `sdk.dir` dans `local.properties` (fichier ignoré par git). Le wrapper Gradle est versionné, avec contrôle SHA-256 de la distribution ; sous Windows, utiliser `gradlew.bat`.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Sur un émulateur smartphone ou un téléphone connecté :

```sh
./gradlew connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.miptv/.MainActivity
```

Aucune clé, aucun compte ni aucune configuration fournisseur n'est nécessaire à la compilation.

## Architecture

Un seul module `app`, en MVVM pragmatique, sans Hilt, Retrofit ni OkHttp. `MiptvApplication` câble à la main la base Room, le chargeur de playlists, le client Xtream et le chiffrement en un seul dépôt pour tout le processus, puis planifie l'actualisation du guide. `LiveViewModel` expose un `StateFlow` que lit l'interface Compose.

### Modèle et données

- `Domain.kt` : modèles (`Category`, `Channel`, `Source`, `ImportResult`, `Movie`, `Series`, `Season`, `Episode`, `SeriesInfo`, `Programme`, `EpgChannel`, `NowNext`, `ProgrammeHit`, `EpgWindow`), `ChannelQuery` (catégorie + filtre) et `searchKey` (forme normalisée des noms). Contrats `ChannelRepository` (catégories, favoris et sources en flux ; listes paginées et compteurs ; sources, favoris, reprise ; guide : `setEpgUrl`, `refreshEpg`, `nowAndNext`, `programmes`, `guideEnd`, `searchProgrammes`, `ensureShortEpg`, `purgeProgrammes`), `M3uAdapter` et `XtreamClient`. Compte Xtream (`XtreamAccount`, `XtreamAccountInfo` avec `blocker()`).
- `Persistence.kt` : entités, DAO et base Room (`miptv.db`, version 4, migrations `MIGRATION_1_2` à `MIGRATION_3_4`), `KeysetPagingSource` et `RoomChannelRepository` : sources M3U et Xtream, catalogue VOD, cache des saisons et épisodes, favoris, positions de reprise et guide (table `programmes`, clé de guide des chaînes, import en flux par table de transit, « maintenant / ensuite », guide du jour, recherche, cache de l'EPG court).
- `Sources.kt` : `Http` (`HttpURLConnection` avec délais et redirections manuelles), `PlaylistLoader` (`AndroidPlaylistLoader` : `Http` pour les adresses, `ContentResolver` pour les URI SAF), messages d'erreur en français sans écho de l'adresse, et `MemoryChannelRepository`, implémentation en mémoire qui sert de référence et de support aux tests.
- `Secrets.kt` : `SecretCipher`, `AesGcmCipher` (AES-256-GCM, nonce aléatoire par valeur, sortie `v1:` + base64) et `KeystoreSecretCipher`, dont la clé vit dans l'Android Keystore.
- `GuideRefresh.kt` : `refreshStaleGuides` (recharge les guides plus vieux qu'un seuil, puis purge) et `GuideRefreshWorker`.

### Lecteurs de formats

- `M3uParser.kt` : implémentation de `M3uAdapter`, lecture en flux depuis un `Reader`/`InputStream`, attributs lus sans expression régulière, adresse du guide du premier en-tête `#EXTM3U`.
- `Epg.kt` : `XmltvParser`, lecteur XMLTV en flux sur SAX (gzip détecté par ses octets de tête, fenêtre temporelle et filtre de chaînes appliqués pendant la lecture, programmes remis par lots, titres en français préférés, programmes sans fin fermés par le suivant, DTD jamais téléchargée) ; rattachement des chaînes au guide (`guideKey`, `guideNameIndex`) ; `resolveOverlaps` (programmes qui se chevauchent ramenés à une seule grille) ; `describeEpgError`.
- `Json.kt` : `JsonStreamReader`, lecteur JSON en flux, et accesseurs tolérants (`nextLongOrNull`, `forEachElement`…) pour les types incohérents des panels Xtream.
- `Xtream.kt` : `XtreamParser` (réponses de `player_api.php` : compte, catégories, chaînes, films, séries, fiches, `get_short_epg`), `HttpXtreamClient`, `XtreamIds` (identifiants `xtream:{source}:…`), `XtreamStreamRef` (référence `xtream://` résolue au moment de lire) et `describeXtreamError`.

### Interface

- `LiveViewModel.kt` : `LiveState` (catégories, favoris, compteurs, requêtes, fiches, `PlayItem` en lecture, positions, rangées récentes, test de connexion, horloge, « maintenant / ensuite », `GuideSheet`, résultats de recherche), listes paginées mises en cache dans le ViewModel, statut d'importation, plein écran, « Épisode suivant », rechargement du guide d'un favori Xtream, état restauré par `SavedStateHandle` (onglet, catégories, recherche, filtres, écran Sources) ; mise en forme des heures et des jours (`formatTime`, `dayLabel`, `guideDaysFrom`).
- `MainActivity.kt` : cinq onglets, Accueil, Recherche, écran Sources, lecteur unique hissé au-dessus des mises en page, plein écran (orientation et barres système) et gestion de Retour.
- `TvScreens.kt` : les trois niveaux de l'onglet TV et la ligne de chaîne (`ChannelRow`).
- `VodScreens.kt` : onglets Films et Séries, fiches, saisons et épisodes, rangées VOD de l'Accueil, groupes Films et Séries de la Recherche.
- `GuideScreens.kt` : ligne « maintenant / ensuite » (`NowLine`), bouton Guide, feuille guide (`guideSection`), groupe Programmes de la Recherche, bloc guide de la fiche source (`SourceGuide`).
- `Preview.kt` : lecteur Media3 (`Playback`, `PlayerSurface`, `LivePlayer`, `Preview`, `VodPlayer`, `FullscreenPlayer`), résolution de l'URL au démarrage et à chaque « Réessayer », en-têtes HTTP (`User-Agent`, `Referer`), délai de chargement borné (`LoadingWatch`), sauvegarde de la position, messages d'erreur.
- `SourcesScreen.kt` : formulaires M3U et Xtream, test de connexion, sélecteur SAF, liste des sources.
- `Design.kt` : palette, icônes (`MiptvIcons`), en-têtes, barre d'onglets, boutons pilule, ligne de progression, badge « DIRECT ». `Splash.kt` : écran d'ouverture et son.

## Choix techniques

### Room plutôt que DataStore

Le catalogue peut compter des dizaines de milliers de chaînes. Room écrit par lots et en transaction (les chaînes d'une source sont remplacées sans toucher aux autres), garde les favoris dans une table séparée reliée par identifiant stable et purge les catégories devenues orphelines. DataStore aurait imposé de réécrire tout le catalogue à chaque étoile. Le prix est le plugin KSP et `room-compiler`.

Le schéma est exporté dans `app/schemas` (versionné) et embarqué dans l'APK de test : `MigrationTest` crée une base v1 remplie à partir de ce fichier, puis la migre et la valide version par version. La CI échoue si le schéma généré diffère de celui du dépôt.

### Listes paginées

Les listes passent par Paging 3. La source Room de `room-paging` a été écartée : elle compte toute la requête à chaque chargement et lit par `LIMIT/OFFSET`, dont le coût croît avec la profondeur. `KeysetPagingSource` reprend chaque page après la dernière ligne lue, `(sourceId, position) > (?, ?)`, sur les index `(sourceId, position)` et `(categoryId, sourceId, position)`, et recharge autour de l'élément affiché après une invalidation. Cocher une étoile n'invalide que la liste et le compteur « Favoris ».

SQLite ne repliant la casse qu'en ASCII, le filtre et la recherche portent sur une colonne `searchName` (minuscules, sans accents) remplie à l'import.

### Client Xtream

Le protocole Xtream Codes n'est pas normalisé : les panels livrent les nombres en chaînes, `null` ou rien, `{}` pour une liste vide, des listes indexées par identifiant, des épisodes indexés par saison ou en tableaux imbriqués. `XtreamParser` lit chaque réponse en flux avec des accesseurs tolérants et ne garde en mémoire que le résultat (30 000 films en 0,4 s en test). Le lecteur JSON est maison parce que `android.util.JsonReader` n'est qu'un stub dans les tests unitaires locaux ; aucune dépendance n'a été ajoutée.

### Guide des programmes

Une playlist désigne un fichier XMLTV ; un fournisseur Xtream expose `xmltv.php`, souvent des dizaines de mégaoctets pour des dizaines de milliers de chaînes, lu pour les seules chaînes favorites. Le fichier est lu en flux par SAX (`javax.xml.parsers`, réel sur l'appareil comme dans les tests JVM, contrairement à `XmlPullParser`), avec la fenêtre `[maintenant − 6 h, maintenant + 3 jours[` et le filtre de chaînes appliqués pendant la lecture. Les programmes sont insérés par lots sous un identifiant de transit, puis échangés avec l'ancien guide en une transaction : un téléchargement interrompu laisse le guide précédent et inscrit l'erreur sur la source. Les programmes qui se chevauchent (grille publiée deux fois à une heure d'écart, par exemple) sont ramenés à une seule grille.

Un programme rejoint une chaîne par la clé `channels.epgKey` : le `tvg-id` quand le guide déclare la chaîne, sinon la chaîne du guide portant le même nom (insensible à la casse et aux accents), sinon le `tvg-id` tel quel ; une chaîne Xtream sans `epg_channel_id` prend `stream:{id}`. « Maintenant / ensuite » est une requête SQL ; la fin du guide d'une chaîne (`guideEnd`) aussi, sur l'index `(sourceId, channelKey, startMs)`. L'horloge de l'interface avance à chaque minute pleine depuis l'écran, jamais depuis le ViewModel, pour rester compatible avec les tests à temps virtuel.

### Actualisation en arrière-plan

WorkManager porte deux travaux autour de `refreshStaleGuides`, tous deux en `KEEP` et sur réseau non facturé : `guide-daily` (périodique, 24 h, batterie non faible, seuil 20 h) et `guide-launch` (une fois à chaque lancement, seuil 24 h, pour les téléphones où le travail périodique est retardé). Le travail relit les sources en base : aucune donnée de source ne transite par ses paramètres. Il finit toujours en `success`, les erreurs restant sur les fiches source. WorkManager est initialisé à la demande par `MiptvApplication` (`Configuration.Provider`, initialiseur automatique retiré du manifeste) : un processus qui ne planifie rien ne le démarre pas.

### Un seul lecteur

`PlayItem` décrit ce qui joue (chaîne, film ou épisode) ; changer d'onglet, de fiche ou ouvrir Sources arrête la lecture. Un fournisseur Xtream limite les connexions simultanées : l'aperçu et le plein écran ne doivent jamais ouvrir deux flux. `rememberPlayback` résout l'URL au démarrage et à chaque « Réessayer », prépare ExoPlayer avec la position de reprise et rapporte la position toutes les 10 s, à la fin et à la fermeture.

### Versions et manifeste

AGP 8.13.2, Gradle 8.13, Kotlin, plugin Compose et KSP 2.0.21, Compose BOM 2024.12.01, Lifecycle 2.8.7, Media3 1.5.1, Room 2.6.1, Paging 3.3.6, WorkManager 2.9.1, Coil 2.7.0, coroutines 1.9.0. Compile et target SDK 35, min SDK 26, bytecode Java 17.

Le manifeste ne demande que `INTERNET`. Il autorise le trafic en clair (`usesCleartextTraffic="true"`) parce que la grande majorité des playlists, portails Xtream et flux IPTV sont servis en `http://`. L'activité déclare `configChanges` : la rotation et le plein écran ne recréent pas l'activité, et le lecteur survit.

## Sécurité et confidentialité

- **Adresses** : les adresses de playlist et de guide peuvent contenir des identifiants. Elles ne sont jamais journalisées ; les messages d'erreur ne les reproduisent pas (seul le code HTTP est affiché) et l'interface n'affiche que le schéma et l'hôte.
- **Comptes Xtream** : le mot de passe est chiffré en AES-256-GCM avec une clé de l'Android Keystore, non exportable, créée au premier chiffrement ; seul `v1:` + base64 est en base. Il n'apparaît ni dans le modèle `Source`, ni dans l'état du ViewModel, ni dans un message. Une chaîne stocke une référence `xtream://{source}/live/{id}.{ext}`, et l'URL complète n'est construite qu'au moment de lire (`playbackUrl`), puis ne vit que dans ExoPlayer ; l'adresse `xmltv.php` n'existe que le temps du téléchargement. Une clé perdue rend `null`, jamais une valeur fausse. `EncryptedSharedPreferences`, dépréciée, n'est pas utilisée.
- **Fichiers locaux** : les URI reçoivent une permission de lecture persistée ; si elle est révoquée, l'actualisation signale « Fichier inaccessible ».
- **Sauvegarde** : `allowBackup="false"`, `dataExtractionRules` (API 31+) et `fullBackupContent` excluent toutes les données de la sauvegarde cloud et du transfert d'appareil à appareil, puisque la clé Keystore ne suivrait pas.
- Aucun identifiant fournisseur en dur, aucun backend, aucune télémétrie.

## Tests et intégration continue

Aucun test ne dépend du réseau. Les réponses Xtream enregistrées et anonymisées, le faux client Xtream et les scénarios de guide partagés vivent dans `app/src/sharedTest`, utilisé à la fois par les tests locaux et les tests instrumentés.

**122 tests unitaires** (`app/src/test`) :

- parseurs : M3U, XMLTV, JSON, réponses Xtream (`M3uParserTest`, `XmltvParserTest`, `JsonStreamReaderTest`, `XtreamParserTest`), dont des volumes de 25 000 chaînes, 30 000 films et 100 000 programmes ;
- `HttpXtreamClient` face à un serveur local (`XtreamClientTest`) ;
- chiffrement AES-GCM (`AesGcmCipherTest`) ;
- dépôts mémoire : sources Xtream et guide (`MemoryXtreamRepositoryTest`, `MemoryEpgRepositoryTest`) ;
- programmes qui se chevauchent (`ResolveOverlapsTest`) ;
- ViewModel : catalogue, sources, Xtream, guide (`LiveViewModelTest`, `XtreamViewModelTest`, `GuideViewModelTest`) ;
- mise en forme et messages d'erreur de lecture (`FormatTest`, `PlaybackErrorTest`).

Les tests ViewModel utilisent `MemoryChannelRepository` avec un chargeur factice et un `StandardTestDispatcher`, et lisent les listes paginées avec `paging-testing`.

**41 tests instrumentés** (`app/src/androidTest`), lancés par `MiptvTestRunner` dans une `Application` neutre : chaque test construit son dépôt, et WorkManager ne démarre pas pendant la suite.

- interface Compose : navigation, sources, lecture et favoris (`PhoneNavigationTest`, 6) ; parcours Xtream (`XtreamPhoneTest`, 4) ; guide (`GuidePhoneTest`, 5) ;
- Room : catalogue et pagination (`RoomChannelRepositoryTest`, 7), sources Xtream (`XtreamRepositoryTest`, 6), guide (`EpgRepositoryTest`, 9), migrations v1 → v4 (`MigrationTest`, 1) ;
- lecteur Media3 face à un serveur local muet (`PlayerTimeoutTest`, `PlayerResolutionTest`) ;
- une mesure sur 50 000 chaînes (`CatalogPerformanceTest`), ignorée par défaut :

```sh
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.perf=true
```

**GitHub Actions** (`.github/workflows/android.yml`), sur chaque push vers `main` et chaque PR vers `main` : un job `build` qui lance les tests unitaires et le lint, compile l'APK et l'APK de test, et vérifie que le schéma Room exporté est commité. Aucun déploiement ni fusion automatique.

Les tests instrumentés ne tournent pas en CI : sur les émulateurs de GitHub (rendu logiciel swiftshader), l'émulateur se figeait par intermittence pendant `GuidePhoneTest.favouritesGetAHomeRow`, à l'appui long sur une chaîne favorite de l'Accueil, sans que l'app plante. Ils se lancent sur un émulateur ou un téléphone avant chaque fusion (`./gradlew connectedDebugAndroidTest`).

Les tests sur appareil réel, notamment avec un vrai fournisseur Xtream et un vrai guide XMLTV, sont décrits dans `VALIDATION.md`, avec leurs résultats.

## Documents du dépôt

- `VALIDATION.md` : scénarios de validation sur appareil et résultats effectifs, sprint par sprint.
- `SPRINT3.md`, `SPRINT4.md`, `SPRINT5.md` : plans et bilans des sprints.

Historique en bref :

- Sprint 1 : portage smartphone, cinq onglets, lecteur Media3.
- Sprint 2 : playlists M3U, catalogue Live TV, lecture, favoris, persistance.
- Sprint 3 : fournisseur Xtream Codes, films et séries, reprise de lecture, identifiants chiffrés.
- Sprint 4 : guide des programmes et refonte de l'interface.
- Sprint 5 : un guide qui reste rempli (favoris Xtream, actualisation WorkManager, feuille guide qui dit ce qui manque).

Références : [barre de navigation Compose](https://developer.android.com/develop/ui/compose/components/navigation-bar), [cycle de vie ExoPlayer](https://developer.android.com/media/media3/exoplayer/hello-world), [Room](https://developer.android.com/training/data-storage/room), [migrations Room](https://developer.android.com/training/data-storage/room/migrating-db-versions), [Paging 3](https://developer.android.com/topic/libraries/architecture/paging/v3-overview), [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager), [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [règles de sauvegarde](https://developer.android.com/guide/topics/data/autobackup), [Coil Compose](https://coil-kt.github.io/coil/compose/), [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider), [format XMLTV](https://github.com/XMLTV/xmltv/blob/master/xmltv.dtd), [SAX sur Android](https://developer.android.com/reference/javax/xml/parsers/SAXParser).
