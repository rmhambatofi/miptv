# miptv — Sprint 1

Application native Android TV / Google TV, sans backend ni compte. Live TV présente catégories, chaînes et aperçu. Accueil, Films, Séries, Recherche et Paramètres sont des destinations ; hors Live TV, elles restent des écrans de présentation, sans catalogue ni moteur de recherche.

## Compilation

Installer Android Studio avec SDK Android 35 et Build Tools, puis utiliser JDK 17 (ou 21). Définir `ANDROID_HOME` ou `sdk.dir` dans `local.properties` (ignoré par Git).

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.miptv/.MainActivity
```

Windows : `gradlew.bat`. Utiliser un émulateur Android TV API 26+ ou un appareil Google TV. Internet est nécessaire pour les vidéos de démo. Aucun fournisseur ni identifiant n'est livré.

## Choix techniques

AGP 8.7.3 / Gradle 8.9, Kotlin et plugin Compose 2.0.21, Compose BOM 2024.12.01, TV Material 1.0.0, Media3 1.5.1 : versions stables épinglées compatibles avec compile/target SDK 35 et bytecode Java 17. Min SDK 26 limite les variantes anciennes. Hilt 2.52 injecte le ViewModel et le dépôt de démonstration ; Coroutines 1.9.0 et StateFlow exposent l'état observable.

Un module `app` avec séparation Domain / repository / ViewModel / UI / player évite une modularisation prématurée. Room 2.6.1, Retrofit 2.11.0 et OkHttp 4.12.0 sont disponibles pour les prochains adaptateurs ; aucune base ni API fictive n'est créée. Les interfaces M3U et Xtream décrivent uniquement des transformations de données. Leur implémentation et la configuration fournisseur sont hors Sprint 1.

Le thème sombre emploie fond #0B0D12, surfaces #181C26, accent #EC3852 et les composants TV Material qui distinguent le focus. La sélection ne déclenche la lecture qu'après OK. Les catégories vides affichent un message et ne piègent pas le focus. Le player est libéré quand on quitte Live TV et mis en pause en arrière-plan.

## Démonstration et droits

Big Buck Bunny et Sintel sont des films ouverts de la Blender Foundation, distribués sous CC BY 3.0 : https://www.bigbuckbunny.org/ et https://www.sintel.org/. Les URL HTTPS du bucket public Google `gtv-videos-bucket/sample` sont des vidéos de démonstration à la demande, explicitement étiquetées comme telles : elles ne simulent pas une vraie diffusion IPTV en direct. Leur disponibilité externe n'est pas garantie. Pas de chaîne commerciale préinstallée, télémétrie ni secret.

## Validation

Deux tests unitaires vérifient la remise à zéro du lecteur au changement de catégorie, les catégories vides, le rejet d'une chaîne hors catégorie et d'une catégorie inconnue.

À vérifier sur Android TV avant livraison : D-pad vers les six destinations ; catégories ↔ chaînes ↔ aperçu ; OK lance le film ; pause/reprise ; changement de catégorie arrête le player ; Favoris vide ; retour vers Accueil ; mise en arrière-plan et retour ; erreur réseau et bouton Réessayer. La validation réelle de décodage et du focus nécessite un appareil/émulateur.

Le dépôt distant ne possède pas encore `main`. La branche de travail est `feature/initial-android-tv` ; une PR nécessite d'abord une branche principale initialisée avec un ancêtre commun. Aucune fusion automatique.

### Résultat dans le cloud

Gradle 8.9 téléchargé avec vérification SHA-256, scripts Gradle configurés jusqu'à la résolution des tâches Android. `testDebugUnitTest assembleDebug` bloque sur `SDK location not found` : aucun test n'a été exécuté, aucun APK produit. Aucun émulateur disponible ; lecture et focus non validés sur appareil. `git diff --cached --check` passe.

Dans ce cloud, utiliser `GRADLE_USER_HOME=/workspace/gradle-cache`, `ANDROID_USER_HOME=/workspace/android-user` et transmettre le proxy à Java/Gradle (`-Dhttps.proxyHost=proxy -Dhttps.proxyPort=8080`). Installer le SDK 35 et définir `ANDROID_HOME` avant de relancer. Ne pas désactiver TLS.
