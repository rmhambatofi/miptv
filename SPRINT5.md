# Plan — Sprint 5 smartphone : un guide qui reste rempli

Objectif : que le guide des programmes **ne paraisse plus incomplet**. Au Sprint 4, le guide d'un fournisseur Xtream ne suit que les favoris et ne se recharge qu'à la main ; un fournisseur réel (validation du 30 octobre 2026 sur le Xiaomi 15) ne publie qu'environ 25 h de programmes et son short EPG n'en donne que 4. L'utilisateur voit alors des jours vides sans savoir pourquoi. Ce sprint charge le guide au bon moment, le tient à jour seul et dit ce qu'il ne peut pas montrer.

Mêmes règles qu'aux sprints précédents : smartphone uniquement (API 26+), aucun identifiant, flux, guide ou contenu livré avec l'application, aucun backend, aucune télémétrie, aucune adresse ni identifiant journalisé.

## Prérequis

- La PR #13 (refonte UX et dédoublonnage du guide) est ouverte vers `main`. Le Sprint 5 part de sa branche, sur `feature/sprint5-guide` ; il sera rebasé sur `main` une fois la PR #13 fusionnée.
- Pour la validation sur appareil : le Xiaomi 15, la source Xtream « POP » et la playlist iptv-org avec son guide XMLTV.

## Décisions de cadrage

- **Le favori déclenche le guide.** Ajouter une chaîne Xtream aux favoris recharge le guide `xmltv.php` de sa source en tâche de fond, sans passer par Sources. Plusieurs étoiles touchées de suite ne font qu'un téléchargement (attente de 3 s après la dernière). Retirer un favori ne télécharge rien : ses programmes partent à l'actualisation suivante.
- **Actualisation quotidienne.** Un travail périodique `WorkManager` (24 h, réseau non facturé, batterie non faible) recharge le guide de chaque source dont le guide date de plus de 20 h, puis purge. Au lancement, un guide de plus de 24 h est rechargé aussi, pour les téléphones où le travail est retardé. Aucun identifiant ne transite par `WorkManager` : le travail relit les sources en base.
- **Dire ce qui manque.** La feuille guide d'une chaîne Xtream hors favoris annonce un guide limité aux prochaines heures, avec un bouton « Ajouter aux favoris ». Les puces des jours au-delà du dernier programme connu de la chaîne sont estompées, et un jour sans programme affiche « Pas encore de programmes pour ce jour » au lieu d'une liste vide.
- **Pas de changement de schéma.** La fin du guide d'une chaîne est une requête sur la table `programmes` (index `(sourceId, channelKey, startMs)` existant) ; Room reste en v4.
- **Version** 0.5.0 (`versionCode` 5).

## Lot 1 — Guide chargé à l'ajout d'un favori Xtream

- **ViewModel** : `toggleFavorite` d'une chaîne Xtream ajoutée aux favoris planifie `refreshEpg` de sa source, avec attente de 3 s regroupant les ajouts suivants ; aucun message hors de Sources : le résultat (nombre de programmes ou `epgError`) s'affiche sur la fiche source.
- **Tests** : `GuideViewModelTest` (un ajout, trois ajouts rapprochés → un seul import, retrait sans import, chaîne M3U sans import).

## Lot 2 — Actualisation quotidienne

- **Dépendance** `androidx.work:work-runtime-ktx`.
- **`refreshStaleGuides(nowMs, maxAgeMs)`** (`GuideRefresh.kt`, nouveau) : recharge le guide des sources dont le guide date de `maxAgeMs` ou plus (ou n'a jamais été chargé), puis purge ; un échec reste sur sa source (`epgError`).
- **`GuideRefreshWorker`** : appelle cette règle, toujours en `success` (les erreurs sont sur les fiches source). Planifié par `MiptvApplication` : travail périodique `guide-daily` (24 h, seuil 20 h) et travail unique `guide-launch` à chaque lancement (seuil 24 h), tous deux en `KEEP` et sur réseau non facturé.
- **Tests** : scénario partagé `staleGuidesAreReloaded` sur les deux dépôts (seuil, source sans guide, échec conservant l'ancien guide, purge). Le travail lui-même, une ligne autour de la règle, est vérifié sur l'appareil (`dumpsys jobscheduler`).

## Lot 3 — Feuille guide honnête

- **Dépôt** : `guideEnd(channelId): Flow<Long?>` (fin du dernier programme connu de la chaîne), en mémoire et dans Room.
- **Interface** : bandeau « Guide limité aux prochaines heures » + « Ajouter aux favoris » pour une chaîne Xtream hors favoris ; puces de jour estompées au-delà de `guideEnd` ; état vide « Pas encore de programmes pour ce jour ».
- **Tests** : scénario partagé pour `guideEnd` ; `GuideViewModelTest` ; `GuidePhoneTest` (bandeau, puce estompée, état vide).

## Lot 4 — Validation et documentation

- Tous les tests instrumentés relancés sur l'émulateur (pas relancés depuis la refonte UX) ; lint.
- Dette du Sprint 4 dans `VALIDATION.md` : scénario 8 (erreur de guide, rejoué avec une adresse `http://` fausse) et purge à 12 h constatée.
- Validation sur le Xiaomi 15 : favori Xtream → guide chargé sans passer par Sources ; feuille guide d'une chaîne hors favoris ; jours estompés ; travail quotidien visible dans `dumpsys jobscheduler`.
- README, `VALIDATION.md` et ce plan mis à jour.

## Hors périmètre

Rattrapage (catch-up, `has_archive`), rappels, grille multi-chaînes, guide complet pour les chaînes non favorites (récemment regardées), images de programmes, Android TV et tablettes.
