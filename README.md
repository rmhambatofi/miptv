# miptv — Sprint 1 Android smartphone

Application native Kotlin pour **smartphones Android uniquement** (API 26+), Jetpack Compose et Material 3. Aucun lanceur Leanback, composant Android TV, navigation D-pad ni orientation paysage imposée. L'application nécessite un écran tactile ; les tablettes ne sont pas une cible de conception ou de validation de ce sprint. Android ne fournit pas de filtre de manifeste permettant d'exclure exclusivement toutes les tablettes tactiles.

## Fonctionnalités

- Barre inférieure tactile : Accueil, TV, Films, Séries, Recherche. Retour système vers Accueil.
- Accueil avec accès rapide aux deux vidéos publiques de démonstration.
- TV mobile : aperçu 16:9, catégories défilantes, chaînes, sélection et catégorie vide.
- Media3/ExoPlayer : commandes tactiles de lecture, pause et déplacement ; chargement, erreur réseau, nouvel essai. Le lecteur est libéré au changement de chaîne/catégorie ou à la sortie de TV, et mis en pause en arrière-plan. Au retour, la reprise est manuelle. Une recréation d'activité conserve la rubrique/catégorie/recherche, sans relancer automatiquement la vidéo.
- Films et Séries : fiches fictives sans lecture ni prétention de catalogue réel.
- Recherche locale des chaînes de démonstration, insensible à la casse.
- Thème sombre, accent rose, surfaces arrondies et prise en compte des barres système/clavier.

## Démarrer

Installer JDK 17 ou 21, Android SDK 35 et Build Tools 35.0.0. Définir `ANDROID_HOME` ou `sdk.dir` dans `local.properties` (ignoré). Le wrapper Gradle est versionné avec contrôle SHA-256 de la distribution.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
# Sur un émulateur smartphone ou un téléphone connecté :
./gradlew connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.miptv/.MainActivity
```

Windows : `gradlew.bat`. Pas de compte, clé, backend ni configuration fournisseur. Une connexion Internet est nécessaire uniquement pour la lecture des démonstrations.

## Architecture et versions

Un module `app` et un MVVM pragmatique :

- `Domain.kt` : modèles et contrats `ChannelRepository`, `M3uAdapter`, `XtreamAdapter`.
- `DemoRepository.kt` : catalogue local synchrone, immuable, et fiches fictives.
- `LiveViewModel.kt` : état `StateFlow`, sélection, recherche, navigation et `SavedStateHandle`.
- `MainActivity.kt` : Compose Material 3, interface tactile et injection manuelle via une factory de ViewModel.
- `Preview.kt` : Media3 et cycle de vie du lecteur.

AGP 8.13.2, Gradle 8.13, Kotlin/plugin Compose 2.0.21, Compose BOM 2024.12.01, Lifecycle 2.8.7 et Media3 1.5.1 sont épinglés. Compile/target SDK 35, min SDK 26, bytecode Java 17. Les bibliothèques inutilisées Hilt, Room, Retrofit et OkHttp du prototype TV ont été retirées. La navigation entre les cinq destinations racines est pilotée par l'état du ViewModel ; aucune pile de détails n'est nécessaire à ce stade.

Les interfaces M3U/Xtream sont des **points d'extension**, pas des importeurs fonctionnels. Les futurs adaptateurs remplaceront le dépôt de démonstration, résoudront les URL et géreront des données autorisées. Transport, authentification, stockage sécurisé et UI de configuration restent à réaliser. Aucun identifiant fournisseur en dur ; ne pas journaliser d'URL contenant des identifiants. Aucun backend n'est créé.

## Démonstration et droits

La rubrique TV lit des **films à la demande**, et non de vraies chaînes IPTV. Big Buck Bunny (2008, Blender Foundation / Peach) et Sintel (2010, Blender Foundation / Durian) sont distribués sous [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/). Attribution et informations originales : [Big Buck Bunny](https://peach.blender.org/about/) et [Sintel](https://durian.blender.org/about/). Les vidéos ne sont pas modifiées ni embarquées ; seules les URL HTTPS publiques de démonstration du bucket Google `gtv-videos-bucket/sample` sont utilisées. Leur disponibilité n'est pas garantie. Aucun contenu commercial, fournisseur illégal, secret ou télémétrie n'est livré.

## Tests et vérification

Huit tests unitaires couvrent : catégorie vide et arrêt de lecture, sélections invalides, sortie de TV, recherche, ouverture d'un résultat, rejet d'une chaîne altérée, restauration sans autoplay et catalogue vide. Deux tests instrumentés Compose vérifient les cinq onglets, la saisie tactile, les résultats et la catégorie vide, sans dépendre d'un flux réseau.

GitHub Actions (`.github/workflows/android.yml`) compile l'APK et l'APK de test, exécute tests unitaires/lint et lance les tests Compose sur émulateur **Pixel 2 API 35**. Les rapports et l'APK debug sont conservés en artefacts. Aucun déploiement ni fusion automatique.

Validation manuelle complémentaire sur smartphone : petit écran et grandes polices, portrait/paysage, découpe et barres système, retour, lecture/pause/déplacement, arrière-plan/reprise, rotation, changement de catégorie pendant la lecture, coupure réseau et nouvel essai. Le décodage réel, les droits de distribution futurs et l'intégration fournisseur ne sont pas validés par les tests du catalogue.

## État initial et branches

Le dépôt initial contenait le prototype TV au commit `66cb377`, sur la seule branche `feature/initial-android-tv`, alors branche par défaut. `main` a été initialisée à partir de ce commit pour conserver l'historique et fournir une base commune. Le portage smartphone est sur `feature/initial-android-phone`, soumis en PR vers `main` sans fusion. La branche par défaut du dépôt n'est pas modifiée automatiquement.

Voir `VALIDATION.md` pour les résultats effectifs de cette livraison.

Références d'implémentation : [barre de navigation Compose](https://developer.android.com/develop/ui/compose/components/navigation-bar), [cycle de vie ExoPlayer](https://developer.android.com/media/media3/exoplayer/hello-world).
