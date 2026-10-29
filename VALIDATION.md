# Validation — smartphone

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
- **`versionCode`.** Une mise à jour avec le même `versionCode` n'a pas été appliquée par l'installeur MIUI. Il passe à 3 (`versionName` 0.3.0) avec le changement de schéma.

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
