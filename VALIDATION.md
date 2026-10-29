# Validation — Sprint 2 smartphone

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

## Lots et commits

| Lot | Commit | Contenu |
| --- | --- | --- |
| Dépendances | `ea26b24` | Room 2.6.1 via KSP 2.0.21-1.0.28, Coil 2.7.0, coroutines 1.9.0 (+ test) |
| 2 — Parsing | `6d2d9cb`, `f33ea22` | `M3uParser` en flux, 8 tests (25 000 chaînes incluses) |
| 1 — Sources | `4cc0807` | Modèle `Source`, contrat asynchrone, chargeur URL/SAF, dépôt mémoire, écran Sources |
| 3 — Catalogue | `1f17177` | TV à partir des chaînes importées, logos Coil, filtre, état sans source |
| 4 — Lecture | `54aa6b0` | Lecteur hissé, plein écran paysage, erreurs Media3 en français, nouvel essai |
| 5 — Persistance | `7457edb` | Room (sources, catégories, chaînes, favoris), catégorie « Favoris » |

Le lot 2 (parseur) a été commité avant le lot 1 parce que l'importation d'une source en dépend ; chaque commit compile et passe ses tests unitaires. Lint n'a été exécuté qu'à partir du lot 1 (le correctif `f33ea22` rend le lot 2 conforme).

## Ce qui a été vérifié

- Parseur : attributs `#EXTINF`, valeurs manquantes (repli titre → `tvg-name` → nom de fichier), lignes vides, BOM, commentaires et directives inconnues, `#EXTVLCOPT` rattaché à la chaîne suivante uniquement, CRLF, attributs sans guillemets, titres et groupes contenant des virgules, identifiants stables entre deux imports, unicité en cas de doublon, fusion des groupes insensible à la casse, 25 000 chaînes depuis un `Reader`.
- ViewModel : catégories « Toutes »/« Favoris »/importées, repli vers « Toutes » et abandon de la sélection lorsque la catégorie ou la chaîne disparaît à l'actualisation, recherche et filtre, restauration de l'onglet/catégorie/recherche/filtre/écran Sources sans autoplay, rejet d'une chaîne falsifiée, adresse non `http(s)` refusée sans appel réseau, hôte injoignable, actualisation en échec conservant les chaînes et enregistrant l'erreur, suppression, plein écran invalide sans sélection et terminé avec elle, favoris conservés après actualisation.
- Lecture : correspondance code d'erreur → message français ; les messages ne contiennent jamais l'adresse du flux.
- Instrumenté (Compose) : cinq onglets, filtrage par catégorie et recherche, écran Sources (adresse invalide puis import valide), état sans source menant à l'ajout puis filtre, aperçu + entrée/sortie du plein écran, étoile et catégorie Favoris. (Room) : import persistant relu par un second dépôt, favoris conservés à l'actualisation, échec conservant les chaînes, suppression purgeant catégories et favoris, échec d'ajout sans source créée.

Sur la CI (émulateur Pixel 2 API 35), le test `sourcesScreenAddsUrlAndRejectsInvalidAddress` a échoué trois fois : sur API 33+, le clavier logiciel reste affiché après `performTextInput`, couvre le bouton « Ajouter l'adresse » (le clic atteint l'IME) et réduit la zone composée de la `LazyColumn`. Reproduit en local sur un AVD API 35 x86_64, corrigé en fermant le clavier (`Espresso.closeSoftKeyboard()`) après chaque saisie et en remplaçant l'adresse en une seule action ; 10/10 sur les AVD API 30 et API 35.

Première exécution instrumentée : 5 échecs de mécanique de test (`performScrollTo()` sur des éléments `LazyColumn` non encore composés, clic sur un bouton hors écran, sélecteur de texte ambigu entre le champ de saisie et la fiche source), corrigés en faisant défiler la liste jusqu'à l'élément (`performScrollToNode`) et en ciblant les `testTag`. Deuxième exécution : 10/10. Pendant cette exécution, le Xiaomi 15 s'est connecté en débogage Wi-Fi et Gradle a tenté d'y installer l'APK : refus HyperOS `INSTALL_FAILED_USER_RESTRICTED` connu, sans rapport avec le code ; l'APK debug a été déposé dans `/sdcard/Download/miptv.apk` pour installation manuelle.

## Validation sur téléphone physique (Xiaomi 15, HyperOS 3 / Android 16)

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

## Limites

- Non vérifié sur appareil : erreur de lecture et nouvel essai, import depuis un fichier SAF, actualisation et suppression d'une source, arrière-plan/reprise, playlist de plusieurs dizaines de milliers de chaînes.
- Les tests instrumentés n'ouvrent aucun flux réseau ; la lecture réelle (HLS/TS, décodage, plein écran effectif, rotation, arrière-plan) et les logos distants nécessitent une validation manuelle avec une playlist réelle.
- Le sélecteur de fichiers SAF n'est pas automatisable en test ; la révocation d'une permission persistée est traitée par le message « Fichier inaccessible » mais n'a pas été exercée.
- Playlists de plusieurs dizaines de milliers de lignes : parseur vérifié sur 25 000 chaînes en test unitaire ; l'insertion Room par lots de 500 et l'affichage `LazyColumn` n'ont pas été mesurés sur appareil.
- Films/Séries restent fictifs ; `XtreamAdapter` reste une interface. Aucun backend, identifiant fournisseur ou contenu n'est livré.
