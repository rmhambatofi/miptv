# Validation — Sprint 1 smartphone

État au 28 octobre 2026 :

- Dépôt inspecté : prototype Android TV, `66cb377`, seule branche distante initiale `feature/initial-android-tv`.
- `main` créée depuis ce commit ; branche smartphone `feature/initial-android-phone`.
- Environnement de cette session : macOS local, JDK 21, Android SDK 35. Aucun environnement Codex distant attaché accessible ; exécution distante prévue par GitHub Actions.
- `testDebugUnitTest` : **8 tests réussis**, zéro échec/erreur.
- `assembleDebug` : **réussi**, APK debug produit.
- `assembleDebugAndroidTest` : **réussi**, APK des deux tests Compose produit. Leur exécution nécessite un émulateur/téléphone ; aucun appareil connecté localement.
- `lintDebug` : **réussi**, zéro erreur ; 15 avertissements (versions de dépendances/SDK plus récentes, configuration de sauvegarde et écran tactile volontairement requis). Une option de thème API 27 incompatible avec minSdk 26 a été retirée.
- Vérification finale locale combinée : **BUILD SUCCESSFUL**, 80 tâches, 19 secondes.
- Première CI distante : échec de préparation du SDK, car setup-android tentait d’installer le paquet obsolète `tools`. La configuration a été corrigée pour installer `platform-tools` uniquement. Nouvelle exécution distante déclenchée.
- Inspection du manifeste de l'APK : lanceur standard `MainActivity`, écran tactile requis, API min 26 / cible 35, aucun lanceur Leanback.
- `git diff --check` : réussi.

Les tests instrumentés ne lisent aucun flux réseau. La lecture, le décodage, les contrôles Media3 et le comportement sur un téléphone physique demandent encore une vérification manuelle. Les vidéos de démo sont externes et peuvent devenir indisponibles. M3U et Xtream restent des contrats d'extension sans import ni authentification.

La CI distante exécute compilation, tests unitaires, lint et tests tactiles sur Pixel 2 API 35 ; son résultat doit être lu sur la PR, sans assimiler des tâches configurées à des tests effectivement réussis. Aucun merge ni déploiement.
