# Validation — Sprint 1 smartphone

Résultats du 28 octobre 2026, code vérifié au commit `739fbaa` :

| Vérification | Résultat |
| --- | --- |
| Tests unitaires | 8 réussis, zéro échec/erreur |
| Analyse Android `lintDebug` | Réussie, zéro erreur, 15 avertissements |
| Compilation APK debug | Réussie en local et sur GitHub Actions |
| Compilation APK des tests Compose | Réussie en local et sur GitHub Actions |
| Tests tactiles instrumentés | 2 réussis sur émulateur smartphone API 35 / Android 15 |
| Manifeste APK | Lanceur standard, écran tactile requis, min API 26 / cible 35, aucune entrée Leanback |
| `git diff --check` | Réussi |

[Exécution distante complète réussie](https://github.com/rmhambatofi/miptv/actions/runs/40644452198) : jobs `build` et `touch-tests` réussis. Le journal confirme `Starting 2 tests on emulator-5554 - 15`, puis `BUILD SUCCESSFUL`. Les rapports et l'APK sont disponibles dans les artefacts Actions. La seconde exécution liée à la PR #2 vérifie le même code.

La vérification finale locale combinée a réussi en 14 secondes (80 tâches). Environnement local macOS, JDK 21, SDK 35 ; environnement distant GitHub Actions Ubuntu/JDK 17 et émulateur Pixel 2 API 35. Aucun environnement Codex distant attaché n'était accessible à cette session.

Les 15 avertissements concernent les mises à jour de dépendances/SDK, la configuration de sauvegarde et l'écran tactile volontairement requis. Ils ne bloquent pas la compilation. Une option de thème API 27 incompatible avec minSdk 26 a été retirée. Le premier échec de préparation CI (paquet SDK obsolète `tools`) a été corrigé par l'installation de `platform-tools` uniquement.

## Dépôt et PR

- État initial : prototype TV `66cb377`, seule branche distante `feature/initial-android-tv`, alors branche par défaut, sans `main`.
- `main` créée depuis ce commit pour fournir une base commune ; travail sur `feature/initial-android-phone`.
- [PR #1](https://github.com/rmhambatofi/miptv/pull/1) ouverte par Codex, puis **fusionnée par le compte `rmhambatofi`** le 28 octobre à 06:30:58 UTC, au commit initial `276d043`. Aucune commande de fusion n'a été lancée par Codex.
- [PR #2](https://github.com/rmhambatofi/miptv/pull/2) ouverte vers `main` pour les corrections postérieures : thème Android 8, préparation de CI, défilement des onglets et bilan. Elle est laissée ouverte, sans fusion automatique.
- Lors du contrôle final, GitHub indique désormais `main` comme branche par défaut ; Codex n'a pas modifié ce réglage.

## Limites

Les tests instrumentés vérifient navigation, recherche et catégorie vide sans lire de flux réseau. Lecture réelle, décodage, commandes Media3, arrière-plan et rotation nécessitent encore une validation manuelle sur téléphone physique. Les vidéos de démonstration sont externes et peuvent devenir indisponibles. Films/Séries sont des fiches fictives ; M3U/Xtream restent des interfaces sans import ni authentification. Aucun backend, identifiant fournisseur ou contenu commercial n'est livré. Les tablettes et Android TV ne font pas partie de la cible de conception/validation.
