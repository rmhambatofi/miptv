# Validation — smartphone

## Sprint 5 — synthèse (30 octobre 2026)

Branche `feature/sprint5-guide` depuis `feature/sprint4-epg` (PR #13 ouverte), un commit par lot ; chaque commit compile et passe `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`, puis `connectedDebugAndroidTest` sur l'émulateur Pixel 2 API 35 x86_64. Plan et bilan dans `SPRINT5.md`.

| Lot | Commit | Contenu | Vérification |
| --- | --- | --- | --- |
| 1 — Favori → guide | `b5b393e` | Étoile sur une chaîne Xtream avec clé de guide : `refreshEpg` de sa source 3 s après la dernière étoile, un seul téléchargement, jamais interrompu | 2 tests `GuideViewModelTest` |
| 2 — Actualisation quotidienne | `425561d` | `refreshStaleGuides`, `GuideRefreshWorker`, travaux `guide-daily` (24 h, seuil 20 h) et `guide-launch` (seuil 24 h) | Scénario partagé `staleGuidesAreReloaded` (JVM et Room) ; travaux vus sur le Xiaomi |
| 3 — Feuille guide honnête | `5443706` | `guideEnd`, bandeau « Guide limité aux prochaines heures », jours estompés annoncés « Sans programme », message du jour vide ; version 0.5.0 | Scénario `guideEndIsTheLastKnownProgramme`, `GuideViewModelTest`, `GuidePhoneTest` |
| 4 — Validation et documentation | ce commit | Suite instrumentée complète, Xiaomi 15, README, VALIDATION, bilan de `SPRINT5.md` | Ci-dessous |

État final : **122 tests unitaires** et **41 tests instrumentés** réussis (dont `CatalogPerformanceTest` ignoré sans `-e perf true`), toute la suite instrumentée relancée pour la première fois depuis la refonte UX ; lint **30 avertissements** (28 au Sprint 4, les deux nouveaux signalent des versions de dépendances plus récentes, dont `work-runtime-ktx`), zéro erreur ; schéma Room v4 inchangé.

### Validation sur appareil (Xiaomi 15, HyperOS 3 / Android 16, 30 octobre 2026, 13:55 – 14:08)

APK debug 0.5.0 installé par l'utilisateur depuis Téléchargements (`lastUpdateTime` 13:55:22). Données en place : playlist iptv-org avec son guide XMLTV (1 275 programmes, chargé à 08:01) et le fournisseur Xtream « POP » (guide de 24 programmes chargé à 11:33 pour son seul favori, beIN Sports 1).

| # | Scénario | Résultat |
| --- | --- | --- |
| 1 | Travaux planifiés | **OK** : au premier lancement (13:56:51), `guide-launch` et la première période de `guide-daily` s'exécutent (`Worker result SUCCESS`, 0,3 s chacun) ; les deux guides ayant moins de 20 h, rien n'est téléchargé, comme prévu. JobScheduler : `guide-daily` avec réseau non facturé et batterie non faible, `guide-launch` avec réseau non facturé. Après un démarrage à froid, WorkManager réinscrit la période suivante (job 2, en attente du délai de 24 h) |
| 2 | Bandeau d'une chaîne hors favoris | **OK** : feuille guide de « \|FR\| Gulli ᵁᴴᴰ » (clé `gulli.fr`) : « Guide limité aux prochaines heures… » avec « Ajouter aux favoris » ; 3 programmes d'aujourd'hui seulement (EPG court), puces Demain, dim. 1 nov. et lun. 2 nov. estompées |
| 3 | Favori → guide sans passer par Sources | **OK** : « Ajouter aux favoris » du bandeau touché à 14:05:50 ; le bandeau disparaît aussitôt ; `epgRefreshedAt` de la source 14:05:53 (attente de 3 s) et la feuille ouverte passe de 3 à 15 programmes aujourd'hui entre 14:06:00 et 14:06:05, sans message. En base : 48 programmes pour les deux favoris (25 beIN Sports 1, 23 Gulli), aucune `epgError` ; les programmes de l'EPG court des autres chaînes sont remplacés par l'import |
| 4 | Jours estompés et jour vide | **OK** : après l'import, Demain porte 9 programmes et n'est plus estompé ; dim. 1 nov. reste estompé et affiche « Pas encore de programmes pour ce jour : le guide connu s'arrête demain à 15:20. » (le fournisseur publie environ 25 h) |
| 5 | Purge à 12 h (dette du Sprint 4, scénario 12) | **OK** : à 14:08, le plus ancien programme conservé finit à 02:10, soit 12,0 h plus tôt, dans les deux sources ; les purges des passages de 13:56 et 14:03 ont retiré tout programme fini depuis plus de 12 h |
| 6 | Erreur de guide (dette du Sprint 4, scénario 8) | **Non rejoué sur l'appareil** : saisir une adresse au clavier par `monkey` aurait remplacé l'adresse du guide de l'utilisateur. Couvert par `EpgScenarios` (hôte injoignable, page HTML, `ftp`) sur les deux dépôts et par `GuideViewModelTest` (messages) |

### Constats

- **HyperOS retire le travail quotidien en arrière-plan.** Démarrage à froid à 14:03:16 : `guide-daily` est réinscrit (job 2, en attente du délai). 40 s après le retour à l'écran d'accueil, JobScheduler ne le connaît plus, alors que WorkManager le tient pour inscrit (`ENQUEUED`, job 2). Sur l'émulateur API 35, le même job reste inscrit. Conséquence : sur ce téléphone, sans « Démarrage automatique » pour miptv, l'actualisation quotidienne n'a lieu qu'au lancement. C'est le rôle de `guide-launch` (guide de plus de 24 h) ; WorkManager réinscrit aussi `guide-daily` à chaque démarrage à froid. Activer le démarrage automatique de miptv dans les réglages HyperOS devrait garder le travail inscrit ; non vérifié.
- Le guide du fournisseur ne couvre qu'environ 25 h : les jours suivants restent estompés, ce que la feuille dit désormais.

## Sprint 4 — synthèse (29 octobre 2026)

Branche `feature/sprint4-epg` depuis `main` (après la PR #8), un commit par lot ; chaque commit compile et passe `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`, puis `connectedDebugAndroidTest` sur l'émulateur Pixel 2 API 35 x86_64 (macOS Intel, JDK 21, SDK 35). La PR #9, ouverte pendant ce sprint, a réparé le fichier de workflow CI, illisible depuis le lot 0.2 du Sprint 3 : aucun run GitHub n'avait vérifié les lots 1 à 4 du Sprint 3.

| Lot | Commit | Contenu | Vérification |
| --- | --- | --- | --- |
| 1 — Parseurs et modèle | `ebf1e2e` | `XmltvParser` en flux (SAX, gzip, fenêtre, filtre, lots), `get_short_epg`, `url-tvg`, modèle `Programme` | 13 tests unitaires, 100 000 programmes filtrés en 0,6 s |
| 2 — Room v4 et import | `6d890f8` | Table `programmes`, clé de guide des chaînes, migration 3 → 4, import en flux avec table de transit, maintenant / ensuite, recherche, EPG court Xtream, guide des favoris | 5 scénarios partagés : 5 tests JVM et 5 instrumentés, étape 3 → 4 de `MigrationTest` |
| 3 — Interface | `2563114` | Cartes TV et Accueil, lecteur, feuille guide, rangée des favoris, groupe Programmes, fiche source | Vérifié à l'écran sur l'émulateur (ci-dessous) |
| 4 — Tests et documentation | ce commit | Tests ViewModel et Compose du guide, README et VALIDATION | Totaux ci-dessous |

État final : **110 tests unitaires** et **36 tests instrumentés** réussis (dont `CatalogPerformanceTest` ignoré sans `-e perf true`), lint **28 avertissements** comme au Sprint 3, zéro erreur, schéma Room v4 exporté et inchangé depuis le lot 2. Aucun test ne dépend du réseau : guides XMLTV et playlists servis par `GuideLoader`, réponses Xtream enregistrées, horloge figée au 29 octobre 2026 à 20 h 30 (`TestClock`).

### Vérification à l'écran sur l'émulateur (lot 3)

Playlist de 5 chaînes et guide XMLTV de 240 programmes datés du jour, générés et servis par le Mac (`http://10.0.2.2:8765`), importés par l'écran Sources : en base, 240 programmes, clés `tf1.fr` (tvg-id), `f2.fr` et `arte.fr` (par le nom, Arte malgré un `tvg-id` périmé), `m6.fr` (tvg-id sans `<channel>`), aucune clé pour la chaîne sans identifiant. Observé : ligne « Journal · barre · Ensuite · 22:45 Météo » sous TF1 dans l'Accueil, la TV et la Recherche, et sous l'aperçu ; feuille guide avec « Lire », puces Aujourd'hui / Demain / sam. 31 oct. / dim. 1 nov., 5 programmes aujourd'hui et 26 demain, programme en cours surligné avec barre, description dépliée au toucher ; rangée « En ce moment sur vos favoris » après l'étoile sur TF1 ; « Programmes · 47 » pour « voyage » et ouverture du guide d'Arte depuis un résultat ; fiche source avec le champ XMLTV prérempli, « Guide · 240 programmes · actualisé le … », « Actualiser le guide ». Un défaut corrigé : le champ XMLTV restait vide quand la carte d'une source supprimée était réutilisée pour une nouvelle source de même identifiant (clé `rememberSaveable`).

### Validation sur appareil (Xiaomi 15, 29 octobre 2026, 21:50 – 22:55)

Les 14 scénarios ont été joués par l'utilisateur avec la playlist iptv-org, le guide public XML TV Fr et le fournisseur Xtream de test (36 621 chaînes). Bilan :

- **10 scénarios vérifiés par adb** : migration 3 → 4 en moins de 400 ms avec 7 205 clés de guide, import d'un guide de 39 607 programmes (base +13 Mo, PSS 210 Mo), rattachement de 13 chaînes par le nom, lecture plein écran avec le programme en cours, rangée des favoris, EPG court Xtream et guide des 4 favoris Xtream téléchargé filtré (260 programmes) sans trace d'identifiant ni d'adresse dans `logcat`, actualisation réimportant chaînes et guide, relance en 327 ms sans réimport, retrait du guide, suppression du fournisseur sans reste ;
- **3 scénarios rapportés par l'utilisateur** (feuille guide, recherche de programmes, et la partie visuelle de plusieurs autres), sans trace possible en base ;
- **1 scénario non observé** : l'erreur de guide (8) n'a laissé ni message ni `epgError` ; à rejouer avec une adresse `http://` fausse ;
- **non mesurés** : les durées d'import (aucun journal applicatif, chrono de l'utilisateur non reporté) et la purge à 12 h, à constater demain.

Constat principal : les programmes des chaînes du guide absentes de la playlist étaient conservés pour rien (38 311 sur 39 607, ≈ 13 Mo) ; corrigé juste après (voir « Constats à traiter »).

### Scénarios à jouer sur le Xiaomi 15

APK debug 0.4.0 du commit `17ae30d` déposé le 29 octobre 2026 à 21:38 dans Téléchargements sous `miptv-0.4.0-sprint4-17ae30d.apk` (installation par l'utilisateur, HyperOS refusant `pm install`). Le téléphone porte alors la version 0.3.0 du Sprint 3 (installée à 16:31) avec une base **v3** : la source iptv-org (198 chaînes) et le fournisseur Xtream de test ré-ajouté à 20:44 (36 621 chaînes, dont 7 205 avec `epg_channel_id`), 36 819 chaînes au total, 2 favoris. Il faut une playlist dont l'en-tête porte `url-tvg`, ou l'adresse d'un guide XMLTV public (par exemple celui publié pour les chaînes françaises d'iptv-org), et le compte Xtream de test. Aucune adresse ni identifiant n'est consigné ici. La colonne Résultat a été remplie au fil des scénarios, de 21:50 à 22:55.

| # | Scénario | Comment | Attendu | Résultat |
| --- | --- | --- | --- | --- |
| 1 | Mise à jour et migration 3 → 4 | Installer l'APK depuis Téléchargements, lancer l'app | Démarrage sans réimport ; `user_version` 4 ; table `programmes` vide ; `channels.epgKey` rempli depuis `tvgId` ; sources, chaînes, favoris intacts | **OK** 21:50 : installé depuis Téléchargements (`lastUpdateTime` 21:50:21) ; premier processus lancé par l'installeur à 21:49:39,87 et Accueil affiché à 21:49:40,24 (`Displayed` +394 ms, migration comprise : schéma seul, `UPDATE` des clés sur 36 819 lignes) ; relance à 21:50:48, +397 ms. `user_version` 4, WAL vidé, base 125 Mo. `channels.epgKey` rempli depuis `tvgId` : 198 sur 198 pour iptv-org, 7 007 sur 36 621 pour le fournisseur (les chaînes sans `epg_channel_id` restent sans clé) ; table `programmes` présente et vide, aucune ligne de transit. Sources, 36 819 chaînes, 805 catégories, 243 438 films, 43 258 séries et les 2 favoris intacts ; `programmeCount` 0, `epgUrl` vide, aucune erreur de guide. Mémoire au repos avant mise à jour : PSS 173 Mo |
| 2 | Guide d'une playlist | Sources → fiche iptv-org → saisir l'adresse XMLTV → « Enregistrer le guide » ; chronométrer | Message « N programmes dans le guide de « … » » ; fiche : « Guide · N programmes · actualisé le … » ; en base, programmes dans la fenêtre −6 h / +3 j seulement ; taille de la base et mémoire (`dumpsys meminfo`) notées | **OK** 22:21 : guide public XML TV Fr (variante large, gzip ≈ 0,9 Mo pour la TNT) enregistré sur iptv-org ; `epgRefreshedAt` 22:21:18, `programmeCount` 39 607, 380 chaînes de guide, aucune erreur, aucune ligne de transit. Fenêtre respectée : du 29 octobre 06:00 (73 programmes de plus de 8 h chevauchant « maintenant − 6 h ») au 2 novembre 23:00. Base de 125 à 138 Mo, WAL vidé ; mémoire après import et pendant la lecture : PSS 210 Mo (173 Mo au repos avant). Aucune occurrence de l'adresse du guide dans `logcat`. Durée de l'import : non mesurable par adb (aucun journal), chrono de l'utilisateur à reporter |
| 3 | Rattachement | TV : chaînes avec `tvg-id` présent dans le guide, chaînes rattachées par le nom, chaînes sans programme | Ligne « maintenant / ensuite » sous les chaînes couvertes, rien sous les autres ; proportion de chaînes couvertes notée | **OK** : les `tvg-id` d'iptv-org (`xxx.fr@SD`) ne figurent pas dans le guide ; 13 chaînes rattachées par le nom affiché (TF1, France 2, 3, 4, 5, 6ter, TFX, LCI, Gulli, NOVO19, M6 Music, NRJ Hits, Ciné+ Classic), les 185 autres gardent leur `tvg-id` comme clé sans programme ; 13 chaînes avec un programme en cours à 22:25, 1 296 programmes rattachés à une chaîne. Ligne « maintenant / ensuite » présente sous ces chaînes seulement (rapporté). **Constat** : 38 311 des 39 607 programmes stockés concernent des chaînes du guide absentes de la playlist (≈ 13 Mo de base) ; voir « Constats » ci-dessous |
| 4 | Lecteur | Lire une chaîne couverte, plein écran, rotation | Programme en cours et « Ensuite » sous l'aperçu et dans l'en-tête plein écran ; barre qui avance ; passage à la minute suivante sans saccade | **OK** : France 4 en lecture à 22:25 en plein écran avec « En ce moment · Gaming X Symphony… Ensuite · Waxx en concert… » dans l'en-tête ; piste audio `started` (processus 6522), décodeurs `c2.dolby.eac3.decoder.eac3` et `c2.qti.avc.decoder` ; `gfxinfo` depuis le lancement (import du guide, listes, lecteur) : 13 110 images, 5,1 % saccadées (1,7 % en mesure historique), médiane 6 ms, 99ᵉ centile 21 ms. Barre et passage à la minute : rapportés par l'utilisateur |
| 5 | Feuille guide | Bouton calendrier d'une chaîne → puces de jour → toucher une description → « Lire » | Programme en cours affiché en premier et surligné ; jours suivants remplis ; description dépliée ; « Lire » ouvre TV sur la chaîne ; Retour ferme la feuille | **Rapporté par l'utilisateur** (feuille guide, jours, description, « Lire », Retour) ; sans trace en base par construction. La feuille d'une chaîne couverte dispose de 1 296 programmes rattachés sur 4 jours |
| 6 | Accueil | Étoile sur deux chaînes couvertes, revenir à l'Accueil | Rangée « En ce moment sur vos favoris » avec les deux chaînes ; toucher lit, le petit calendrier ouvre le guide | **OK** 22:3x : 5 favoris en base (BFM Business, RMC Story, France 2, France 4 et une chaîne Canal+ du fournisseur) ; Accueil : rangée « En ce moment sur vos favoris » avec « France 2 · Envoyé spécial · Ensuite · 23:00 Complément d'enquête » et « Canal+ · Le sens de l'humour · Ensuite · 23:20 … » (France 4 en cours aussi en base). Toucher et petit calendrier : rapportés |
| 7 | Recherche de programmes | Recherche → un mot d'un titre de programme | Groupe « Programmes · n » ; un résultat ouvre le guide de la chaîne au bon jour, programme visible | **Rapporté par l'utilisateur** ; la recherche porte sur les 1 296 programmes rattachés (les 38 311 orphelins en sont exclus par la jointure) |
| 8 | Erreur de guide | Fiche source → adresse XMLTV fausse puis injoignable → « Enregistrer le guide » | Message en rouge sans l'adresse ; `epgError` sur la fiche ; les programmes précédents restent affichés | **Non observé** : à la lecture, la source iptv-org garde son adresse `https://…`, `epgError` vide, `epgRefreshedAt` 22:21:18 et ses 39 607 programmes. Une adresse `ftp://` est refusée avant enregistrement (message seul) ; une adresse `http(s)` injoignable aurait inscrit « Hôte introuvable… » sur la fiche. À préciser par l'utilisateur, ou à rejouer avec une adresse `http://` fausse |
| 9 | Fournisseur Xtream : EPG court | Ajouter le fournisseur de test (ou l'actualiser) ; dans TV, toucher une chaîne Xtream non favorite | Après quelques secondes, programme en cours et « Ensuite » sous l'aperçu ; `logcat` sans `player_api` ni identifiant ; une seule requête `get_short_epg` par chaîne en 30 min | **OK** (observé dès 22:3x, avant le scénario) : 12 programmes pour 3 chaînes du fournisseur en base après leur sélection, dont une chaîne sans `epg_channel_id` servie par la clé `stream:{id}` ; aucune ligne `get_short_epg`, `player_api` ni `xmltv.php` dans `logcat`. Le fournisseur renvoie des entrées qui se chevauchent (deux programmes en cours sur Canal+), l'application n'en affiche qu'un. Ligne sous l'aperçu : rapportée |
| 10 | Fournisseur Xtream : guide des favoris | Étoile sur 3 chaînes Xtream → fiche source → « Actualiser le guide » ; chronométrer | Téléchargement de `xmltv.php` filtré : « N programmes dans le guide de « … » », N borné aux 3 chaînes et 3 jours ; mémoire stable pendant l'import (lecture en flux) | **OK** 22:46:20 : 4 favoris Xtream avec clé (Canal+, Planète+, Toute l'Histoire, Histoire) → « Actualiser le guide » télécharge `xmltv.php` filtré : `programmeCount` 260 (81, 88, 69 et 22 programmes par chaîne, du 29 octobre 14:38 au 31 octobre 01:00, le fournisseur ne publiant qu'environ deux jours), aucune ligne de transit, les programmes de l'EPG court remplacés. Mémoire après coup : PSS 264 Mo. Durée : chrono de l'utilisateur à reporter. Qualité des données du fournisseur : doublons à une minute d'écart et chaîne « ne fait plus partie des offres » |
| 11 | Actualisation de la source | Fiche source → « Actualiser » | Chaînes et guide réimportés ; clés par le nom recalculées ; favoris conservés | **OK** 22:46:44 : « Actualiser » sur iptv-org réimporte chaînes et guide dans la même opération (`lastRefreshed` = `epgRefreshedAt` = 22:46:44) : 39 609 programmes (guide mis à jour entre-temps), 13 clés par le nom recalculées, les 4 favoris iptv-org conservés |
| 12 | Redémarrage et purge | `am force-stop` puis relance le lendemain (ou horloge avancée) | Programmes finis depuis plus de 12 h absents ; « maintenant » à jour ; aucun réimport | **OK pour le redémarrage** 22:50:41 : processus relancé, Accueil affiché en **327 ms**, `lastRefreshed` inchangé (22:46:44), aucun réimport ; « maintenant » recalculé à l'ouverture. **Purge non observable ce soir** : aucun programme n'est fini depuis plus de 12 h (le plus ancien a commencé le matin et se termine après 16 h) ; couverte par `EpgScenarios.purgeDropsFinishedProgrammes` sur les deux dépôts, à constater au premier lancement de demain |
| 13 | Retrait du guide | Fiche source → vider l'adresse → « Retirer le guide » | Plus aucune ligne « maintenant » ; `programmes` vide pour la source ; « Aucun guide » sur la fiche | **OK** : source iptv-org avec `epgUrl`, `epgRefreshedAt` et `epgError` vides, `programmeCount` 0, table `programmes` vide pour la source ; les clés de guide des chaînes restent (13 par le nom), sans effet. « Aucun guide » sur la fiche et disparition des lignes « maintenant » : rapportés |
| 14 | Suppression de la source | Fiche source → « Supprimer » | Programmes et clés de la source disparus avec elle | **OK** : plus aucune source ni chaîne Xtream, aucun programme résiduel (table `programmes` vide, aucune ligne de transit), tables films, séries, catégories VOD, saisons, épisodes et positions vides ; les 4 favoris iptv-org conservés (BFM Business, RMC Story, France 2, France 4). Mémoire après suppression : PSS 181 Mo |

### Constats à traiter

- **Programmes orphelins conservés** : pour une playlist, l'import garde tous les programmes de la fenêtre, y compris ceux des chaînes du guide qu'aucune chaîne de la playlist ne désigne (38 311 sur 39 607 avec iptv-org et le guide XML TV Fr, soit ≈ 13 Mo). Ils ne servaient ni à l'affichage ni à la recherche (jointure sur `epgKey`). **Corrigé après la validation** : les deux dépôts suppriment, après le rattachement des clés, les programmes de la source qu'aucune chaîne ne désigne (`deleteOrphanProgrammes`) ; le scénario partagé attend désormais 9 programmes rattachés au lieu de 10, et `programmeCount` compte ce qui est affichable. Avec iptv-org et le guide XML TV Fr, la base ne gardera que les 1 296 programmes des 13 chaînes couvertes ; à constater à la prochaine actualisation du guide sur le Xiaomi.

## Sprint 4 — lot 1 : parseurs XMLTV et EPG court (29 octobre 2026)

13 tests unitaires (99 au total) : `XmltvParserTest` (chaînes, décalages, langues, entités et CDATA, programme sans fin fermé par le suivant, fenêtre, filtre de chaînes, lots, gzip, HTML, document vide ou tronqué, heures partielles et décalages aberrants, numéros `xmltv_ns`, base64 prudent, progression d'un programme, 100 000 programmes de 200 chaînes filtrés sur 50 favorites et 72 h en 623 ms sur le Mac), `shortEpg` (base64, horodatages puis heures locales, listes vides ou en objet), en-tête `url-tvg` dans `M3uParserTest`, `get_short_epg` dans `XtreamClientTest` (paramètres `stream_id` et `limit`). Lint inchangé (28).

## Sprint 4 — lot 2 : Room v4 et import du guide (29 octobre 2026)

Migration 3 → 4 égale au schéma exporté `4.json`, validée par `MigrationTest` depuis une base v1 remplie (clés de guide `bbb`, `sintel`, `null`). Cinq scénarios `EpgScenarios` sur les deux dépôts (JVM et Room) : import d'une playlist avec `url-tvg` et rattachement par `tvg-id`, nom (France 2, Arte malgré un `tvg-id` périmé) et clé nue (M6 sans `<channel>`) ; « maintenant / ensuite » à 20 h 30 pour quatre chaînes (M6 commence exactement à l'heure, Arte sans suivant) ; guide du jour et du lendemain (programme à cheval sur minuit) ; recherche « voyage » (3 résultats triés, programmes finis et orphelins exclus) ; adresse `ftp` refusée, hôte injoignable et page HTML conservant l'ancien guide avec `epgError`, guide valide ensuite, retrait ; actualisation de la playlist réimportant le guide à l'adresse choisie par l'utilisateur ; fournisseur Xtream sans favori (rien téléchargé), EPG court à la sélection (clé `stream:{id}` sans `epg_channel_id`, cache), guide des favoris filtré, échec conservant les programmes, suppression ; purge après 12 h. Côté Room en plus : table de transit vide après import, clés `epgKey` en base. Totaux : 104 unitaires, 32 instrumentés. Premier échec, corrigé : `generateSequence` s'arrêtait à la première clé `null` dans le test de migration.

## Sprint 4 — lot 3 : interface du guide (29 octobre 2026)

Vérification à l'écran décrite dans la synthèse ; tests existants (104 unitaires, 32 instrumentés) inchangés après les modifications des cartes, de l'aperçu et de la fiche source.

## Sprint 4 — lot 4 : tests et documentation (29 octobre 2026)

`GuideViewModelTest` (6) : horloge, favoris et chaîne sélectionnée ; feuille guide chargée, jour suivant, chaîne sans clé, fermeture par Sources ; recherche de programmes, ouverture du guide sur le résultat, « Lire » depuis le guide ; adresse du guide (enregistrée, refusée, injoignable, retirée) avec les messages en français ; EPG court Xtream à la sélection et à l'ouverture ; mise en forme des heures et des jours en heure de Paris. `GuidePhoneTest` (4 Compose) : cartes avec programme en cours et chaîne sans ligne, feuille guide sur deux jours puis « Lire » et ligne sous l'aperçu ; rangée « En ce moment sur vos favoris » et son bouton guide ; résultat de recherche ouvrant le guide ; champ XMLTV de la fiche source (préremplissage, enregistrement, état, « Actualiser le guide »). README et VALIDATION réécrits ; `SPRINT4.md` tient le bilan.

## Sprint 3 — synthèse (29 octobre 2026)

Branche `feature/sprint3-xtream`, un commit par lot ; chaque commit compile et passe la commande CI locale `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`, puis `connectedDebugAndroidTest` sur l'émulateur Pixel 2 API 35 x86_64 (macOS Intel, JDK 21, SDK 35).

| Lot | Commit | Contenu | Vérification |
| --- | --- | --- | --- |
| 0.1 — Schéma exporté | `8a99470` | `exportSchema`, `app/schemas` versionné, `MigrationTest` sur une base v1 remplie | CI : le schéma généré doit être commité |
| 0.2 — Pagination | `7836642` | Pagination par clé depuis Room, favoris sans réémission du catalogue | Mesures sur 50 000 chaînes ci-dessous |
| 0.3 — Validation appareil | `635ee4b` | Scénarios du Sprint 2 sur le Xiaomi 15 | Section dédiée ci-dessous |
| 0.4 — Délai borné | `430bc42` | Erreur et « Réessayer » après 20 s de chargement | `PlayerTimeoutTest`, Xiaomi 15 |
| 1 — Client Xtream | `5c36b25` | Parseurs en flux, client HTTP, URL de lecture, erreurs en français | 33 tests unitaires, 30 000 films en 0,44 s |
| 2 — Identifiants et Room v3 | `6023f64` | Mot de passe chiffré (Keystore), tables VOD, caches, favoris, reprise, migration 2 → 3 | 9 unitaires, 7 instrumentés |
| 3 — Interface | `b381fd8` | Formulaire Xtream, Films, Séries, lecteur VOD unique, Accueil, Recherche groupée | 6 unitaires, 4 Compose |
| 4 — Tests et documentation | ce commit | Tests du lecteur VOD et des formats, README et VALIDATION réécrits | Totaux ci-dessous |

État final : **86 tests unitaires** et **27 tests instrumentés** réussis, lint **28 avertissements** (mises à jour de dépendances épinglées, cible API, écran tactile requis, un `InputStream` fermé par l'appelant), zéro erreur, schéma Room v3 inchangé depuis le lot 2. Aucun test ne dépend du réseau : réponses Xtream enregistrées et anonymisées, serveurs locaux sur `ServerSocket`, faux client, clé AES générée sur place.

### Validation sur appareil (Xiaomi 15, compte Xtream de test, 29 octobre 2026)

Les 17 scénarios ci-dessous ont été joués par l'utilisateur sur son compte Xtream réel (fournisseur de test, 28 551 chaînes, 161 342 films, 63 798 séries). Bilan :

- **14 scénarios vérifiés par adb** (base copiée via `run-as`, `logcat`, `dumpsys`) : migration 2 → 3 en moins d'une seconde, ajout du fournisseur en une minute environ, mot de passe chiffré `v1:` sans aucune occurrence en clair dans la base ni dans `logcat`, lecture TV, film et épisode avec positions en base, 2,4 % d'images saccadées sur les grilles, mémoire 320 Mo en régime établi, relance en 453 ms sans réimport, clé Keystore relue après redémarrage, suppression du fournisseur qui vide toutes les tables VOD et ses favoris et positions ;
- **3 scénarios rapportés par l'utilisateur seulement** : 13 (actualisation du fournisseur), 14 (arrière-plan pendant un film) et 15 (mode avion). Les trois dépendent d'un état transitoire que la coupure du débogage Wi-Fi (mode avion, redémarrage) puis la suppression du fournisseur ont effacé avant lecture ; leurs messages d'erreur sont couverts par les tests automatisés ;
- **un écart observé** : le scénario 11 n'avait d'abord posé que le favori film ; le favori série a été posé et vérifié en base ensuite.

Restent hors de portée sans second appareil ou sans compte dédié : la perte de clé après restauration (« Identifiants à ressaisir », couverte par `XtreamRepositoryTest`), un compte expiré ou saturé en connexions sur un vrai panel, et l'hétérogénéité des panels inconnus.

### Scénarios à jouer sur le Xiaomi 15

APK debug du commit `07bf612` déposé le 29 octobre 2026 dans Téléchargements sous `miptv-0.3.0-sprint3-07bf612.apk`. Le téléphone porte alors la version du lot 0.4 (installée à 13:27) avec une base **v2** de 3 sources, 50 204 chaînes et 2 favoris. L'utilisateur manipule le téléphone et saisit lui-même ses identifiants ; l'état est lu par `uiautomator dump`, captures d'écran, `dumpsys` et copie de la base via `run-as`. La colonne Résultat a été remplie au fil des scénarios, entre 16:31 et 19:43.

| # | Scénario | Comment | Attendu | Résultat |
|---|---|---|---|---|
| 1 | Mise à jour et migration 2 → 3 | Gestionnaire de fichiers → Téléchargements → toucher l'APK, confirmer. Lancer l'app, chronométrer jusqu'au résumé de l'Accueil | `lastUpdateTime` du jour ; `user_version` 3 ; les 3 sources, 50 204 chaînes et 2 favoris toujours là ; Films et Séries affichent « Aucun fournisseur Xtream » | **OK** 16:31 : installé depuis Téléchargements ; processus lancé à 16:32:51,76, base écrite en v3 à 16:32:52,07 (migration < 1 s : schéma seul, aucune réécriture des 50 204 lignes) ; `Displayed` +420 ms ; 12 tables et 11 index conformes à `3.json` ; Accueil « 50204 chaînes dans 530 catégories, depuis 3 source(s) », 2 favoris ; Films : « Aucun fournisseur Xtream » et bouton « Ajouter un fournisseur » |
| 2 | Test de connexion refusé | Sources → « Fournisseur Xtream » → serveur, identifiant, mot de passe **faux** → « Tester la connexion » | « Identifiants refusés… » en rouge, aucune source créée | **OK** (joué par l'utilisateur, 16:35–16:38) ; aucune source créée en base avant l'ajout |
| 3 | Test de connexion accepté | Corriger le mot de passe → « Tester la connexion » | « Compte active jusqu'au … · n/m connexion(s) · formats … », rien n'est enregistré | **OK** (joué par l'utilisateur) |
| 4 | Ajout du fournisseur | « Ajouter » ; chronométrer | « N chaînes, M films et S séries importés depuis « … » » ; fiche source : « Xtream · http://hôte:port · identifiant », date de fin de compte, compteurs. En base : `encryptedPassword` commence par `v1:`, aucune occurrence du mot de passe dans `miptv.db` ni dans `logcat` | **OK** : « 28551 chaînes, 161342 films et 63798 séries importés depuis « … » » (fournisseur de test réel, hôte et identifiant non consignés) ; import terminé à 16:39:21, environ 1 min après la dernière saisie ; en base : source 4 `XTREAM`, `encryptedPassword` = `v1:` + 48 caractères, `expiresAt` 17/02/2027, 78 755 chaînes au total dont 28 551 `xtream://`, 958 catégories, 161 342 films, 63 798 séries, 301 catégories VOD ; aucune URL avec identifiants en base ; logcat : aucune ligne de l'app avec `player_api`, `password=` ou chemin `/live/…` (les seules occurrences de « password » sont celles de Gboard). Base 118 Mo + WAL 105 Mo ; mémoire juste après l'import : PSS 520 Mo (Java 161 Mo, natif 244 Mo), à revérifier en régime établi. Remarque : la lecture d'écran par `uiautomator` capture le mot de passe lorsque « Afficher » est actif ; captures supprimées |
| 5 | TV avec les chaînes Xtream | Onglet TV : catégories du fournisseur, filtre, lecture d'une chaîne, plein écran, rotation, retour | Logos, lecture dans les 20 s, « Chargement… » puis commandes ; `dumpsys audio` montre la piste `started` ; aucune URL dans `logcat` | **OK** (joué par l'utilisateur) : chaînes Xtream présentes dans TV (78 755 chaînes au total), lecture d'une chaîne observée côté système par la création puis la libération d'un `AudioTrack` de l'app ; aucune URL de flux ni identifiant dans logcat (seules les lignes du décodeur, URL absentes) |
| 6 | Films : grille et fiche | Films → chips de catégories, filtre par titre, toucher une affiche | Grille 3 colonnes avec affiches Coil, défilement fluide (`dumpsys gfxinfo`) ; fiche avec affiche, année · durée · note, synopsis chargé en moins de 3 s | **OK** : `gfxinfo` depuis la remise à zéro (TV, grilles Films et Séries sur 161 342 films et 63 798 séries, fiches, lecteur) : 15 323 images, **2,4 % saccadées**, médiane 6 ms, 90ᵉ centile 13 ms, 99ᵉ 20 ms ; 2 fiches film complétées depuis le fournisseur (`detailsLoadedAt`) ; mémoire en régime établi après les lectures : PSS 320 Mo (Java 99 Mo, natif 49 Mo), contre 520 Mo juste après l'import |
| 7 | Lecture d'un film et reprise | « Lire », attendre 30 s, avancer et reculer de 10 s avec le contrôleur, plein écran, fermer le lecteur (croix), rouvrir la fiche | Lecture démarre ; ±10 s effectifs ; à la réouverture « Reprendre à N min » et « Recommencer » ; « Reprendre » repart à la position | **OK** (joué par l'utilisateur) : ligne `progress` du film à 81 s sur 1 227 s, écrite à 16:51:35 à la fermeture du lecteur ; décodeurs `c2.android.aac.decoder` (audio) et `c2.qti.avc.decoder` (vidéo H.264 matériel), piste audio créée à 16:49:26 et libérée à 16:50:46 ; aucun crash |
| 8 | Séries : saisons, épisodes, suivant | Séries → fiche → changer de saison → lire un épisode → « Épisode suivant » | Chips de saison avec compteurs ; liste d'épisodes (miniature, durée) ; lecture ; le bouton enchaîne sur l'épisode suivant (titre « S01E02 · … ») ; à la fin réelle d'un épisode court, le bouton apparaît aussi en plein écran | **OK** (joué par l'utilisateur) : 1 série détaillée en cache (`seasons` 1, `episodes` 20) ; trois épisodes consécutifs lus par « Épisode suivant » avec positions écrites à 16:52:57, 16:53:04 et 16:53:14 (19 s, 5 s puis 1 235 s sur 3 240 s après un saut avant) ; chaque enchaînement libère la piste audio précédente et en crée une nouvelle (un seul lecteur). Fin réelle d'un épisode non observée (épisodes de 50 min) |
| 9 | Accueil : Reprendre et récents | Revenir à l'Accueil après 7 et 8 | Rangée « Reprendre » (film et épisode, barre de progression, « Reprendre à N min »), « Films récents », « Séries récentes » ; toucher une carte Reprendre ouvre la fiche avec le lecteur à la position | **OK** (joué par l'utilisateur) : en base, l'épisode repris depuis l'Accueil est réécrit à 16:56:12 (1 238 s, soit la position sauvegardée + 3 s de lecture) et un second film repris à 16:58:56 (1 294 s sur 6 693 s) ; `recentMovies`/`recentSeries` ordonnés par `addedAt`/`last_modified` du fournisseur (films du jour en tête) ; fluidité Accueil + Recherche : 7 898 images, 1,5 % saccadées, médiane 6 ms |
| 10 | Recherche groupée | Recherche → un mot présent dans une chaîne, un film et une série | Trois groupes « Chaînes · n », « Films · n », « Séries · n » ; un film ouvre sa fiche dans Films, une série dans Séries, une chaîne joue dans TV | **OK** (joué par l'utilisateur) : une troisième fiche film complétée depuis le fournisseur pendant ce scénario (`detailsLoadedAt` : 3 films) ; pistes audio ouvertes puis libérées à 16:58:45 et 16:58:53 |
| 11 | Favoris films et séries | Étoile sur une affiche et dans une fiche ; chip « Favoris » dans Films et Séries | Les favoris apparaissent sous « Favoris » dans chaque onglet ; en base, lignes `favorites` avec `xtream:…:movie:` et `xtream:…:series:` | **Partiel** : un favori film en base (`xtream:4:movie:…`, 3 favoris au total avec les 2 chaînes M3U) ; aucun favori série posé pendant le scénario, à refaire au scénario 13 avant l'actualisation pour vérifier sa conservation |
| 12 | Redémarrage sans réimport | `am force-stop com.miptv` (adb) puis relance | Catalogue, favoris, « Reprendre » intacts ; `lastRefreshed` inchangé ; la reprise d'un film repart bien à sa position (Keystore relu) | **OK** (adb, 17:00) : `am force-stop` puis `am start -W` : Accueil affiché en **453 ms** avec « 78755 chaînes, 161342 films et 63798 séries, depuis 4 source(s) », rangée « Reprendre » (film « … · Reprendre à 21 min », épisode « S01E03 · … · Reprendre à 20 min ») et « Films récents » ; en base, 3 favoris et 5 positions inchangés, `lastRefreshed` du fournisseur toujours 16:39:21. Lecture après relance (clé Keystore relue) : voir scénario 16 |
| 13 | Actualisation du fournisseur | Fiche source → « Actualiser » ; chronométrer | Compteurs et date mis à jour ; favoris et positions conservés ; rouvrir une série recharge ses saisons (cache vidé puis rempli) | **Rapporté par l'utilisateur** (rejoué vers 17:16 après la première passe, sans observation adb) : compteurs et date mis à jour, favoris et positions conservés. La première passe (avant 17:11) n'avait laissé aucune trace en base et la seconde n'a pu être lue : la copie de la base prise pendant l'actualisation était incohérente (écriture en cours), puis le mode avion du scénario 15 a coupé la liaison adb Wi-Fi et la suppression du scénario 17 a effacé la fiche source, donc `lastRefreshed` et `lastError` du fournisseur. Seule trace indirecte : la source iptv-org porte `lastRefreshed` 17:16:48, donc une actualisation a bien été lancée depuis l'écran Sources à ce moment |
| 14 | Arrière-plan pendant un film | Lancer un film, bouton Accueil du téléphone, revenir | Lecture en pause au retour, reprise manuelle ; position sauvegardée (vérifiable dans `progress`) | **Rapporté par l'utilisateur** ; sans trace exploitable après le redémarrage (journaux perdus). Les positions des lectures suivantes sont bien en base |
| 15 | Réseau coupé | Mode avion → « Actualiser » puis « Lire » un film | Erreur en français sur la source (« Hôte introuvable… ») et dans le lecteur (« Connexion au flux impossible… » puis « Réessayer ») ; aucun hôte, identifiant ni mot de passe dans les messages | **Rapporté par l'utilisateur** (rejoué vers 17:21) : « Actualiser » affiche l'erreur réseau en français sur la fiche source, « Lire » affiche « Connexion au flux impossible… » puis « Réessayer », sans hôte ni identifiant dans les messages. Non observable par adb : le mode avion coupe le débogage Wi-Fi (dernière lecture à 17:21:17, liaison perdue à 17:21:32), et la fiche source où `lastError` aurait été lue a été supprimée au scénario 17 avant le retour du débogage (19:43). Les textes affichés sont ceux vérifiés par `XtreamParserTest`, `XtreamClientTest` et `PlayerTimeoutTest` |
| 16 | Redémarrage du téléphone | Redémarrer, relancer l'app, lire un film | La clé Keystore survit : lecture possible sans ressaisie | **OK** : redémarrage vers 17:08 (`uptime` 3 min à 17:11), processus lancé à 17:09:53, piste audio de l'app créée à 17:10:05 et libérée à 17:10:08, position de l'épisode écrite à 17:10:08 (1 160 s sur 2 650 s) : l'URL a donc été résolue avec le mot de passe déchiffré par la clé Keystore relue. Accueil « Reprendre à 19 min » / « Reprendre à 21 min ». Aucune URL ni identifiant dans logcat depuis le redémarrage. (Une exception fatale à 17:08:43 appartient à l'application franceinfo, sans rapport) |
| 17 | Suppression du fournisseur | Fiche source → « Supprimer » | Films et Séries reviennent à « Aucun fournisseur Xtream » ; TV ne garde que les chaînes M3U ; `favorites` et `progress` du fournisseur vides ; tables `movies`, `series`, `seasons`, `episodes`, `vod_categories` vides | **OK** (base relue à 19:43 au retour du débogage sans fil, `integrity_check` ok) : plus aucune source `XTREAM` ; `channels` ne contient plus aucun identifiant `xtream:` ni référence `xtream://` ; `categories` sans entrée `xtream:` ; `movies`, `series`, `vod_categories`, `seasons`, `episodes` et `progress` vides (les 6 positions du fournisseur ont disparu) ; `favorites` ne garde que les 2 chaînes M3U (BFM Business, RMC Story), les 2 favoris film et série du fournisseur sont partis. Il ne reste que la source iptv-org (198 chaînes, 29 catégories) : la playlist de 50 000 chaînes avait été supprimée à la première passe et la source fichier `miptv-test.m3u` (6 chaînes) l'a été pendant cette passe. La première passe (avant 17:11) avait supprimé la playlist de 50 000 chaînes au lieu du fournisseur : ses 50 000 chaînes et 100 catégories avaient bien disparu (base de 118 à 105 Mo) et le fournisseur était resté intact (28 551 chaînes, 161 342 films, 63 798 séries, 2 favoris, 6 positions), ce qui valide aussi la suppression ciblée d'une source M3U au milieu d'autres |

Hors de portée sans second appareil : la perte de clé après restauration (scénario « Identifiants à ressaisir »), couverte par `XtreamRepositoryTest`.

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
- **Installation.** La première mise à jour n'a pas été appliquée : l'installeur s'était ouvert sur l'ancienne entrée MediaStore de `miptv.apk`, le fichier ayant été redéposé sous le même nom. Le `versionCode` identique n'y est pour rien : au lot 0.4, un APK de même `versionCode` (3), déposé sous un nouveau nom, a bien été installé. Le `versionCode` passe tout de même à 3 (`versionName` 0.3.0) avec le changement de schéma, et l'APK est désormais déposé sous un nouveau nom.

## Sprint 3 — lot 0.4 : délai de chargement borné (29 octobre 2026)

Un flux qui ne répond pas restait sur « Chargement… » : Media3 relance seul les erreurs réseau, et un serveur qui accepte la connexion sans rien envoyer le fait attendre sans fin. Désormais, après 20 s de chargement continu alors que la lecture est demandée (au démarrage comme sur un direct qui se fige), le lecteur s'arrête et affiche « Le flux ne répond pas après 20 secondes. » avec « Réessayer ». Une pause, y compris le passage en arrière-plan, arrête le décompte.

### Tests

- Unitaires : 32/32, dont deux sur `LoadingWatch` (expiration à la limite, remise à zéro après une pause ou la fin du chargement).
- Instrumentés : 15/15 sur l'émulateur, dont `PlayerTimeoutTest` : un vrai lecteur Media3 face à un serveur local qui garde la connexion ouverte sans répondre, limite réduite à 3 s. L'erreur apparaît, « Réessayer » relance un chargement, interrompu à son tour.
- Lint : 29 avertissements, aucun nouveau, aucune erreur.

### Xiaomi 15

Chaîne « Flux muet » ajoutée au fichier de test, pointée vers un serveur du Mac qui accepte la connexion et n'envoie rien. Les temps sont mesurés par `uiautomator dump`, donc à environ 2,5 s près.

| Scénario | Résultat |
|---|---|
| Lecture depuis la recherche | « Chargement… », puis le message et « Réessayer » entre 20 et 22,6 s après le tap |
| « Réessayer » | Nouveau chargement, à nouveau interrompu au plus 22,4 s après le tap |
| Arrière-plan 5 s après le lancement, 30 s dehors, puis retour | Aucun message des 20 s pendant la pause. Le lecteur reste en pause au retour. Media3 continue toutefois de charger pendant la pause et finit par échouer de lui-même (« Connexion au flux impossible. Vérifiez votre réseau. » et « Réessayer ») 66 à 76 s après le lancement, ce qui reste une fin bornée |

## Sprint 3 — lot 1 : client Xtream et modèle (29 octobre 2026)

Lot testable sans appareil ni compte : aucun réseau, réponses JSON enregistrées et anonymisées (`XtreamFixtures`).

- Unitaires : **65/65** (32 existants, 33 nouveaux : 9 `JsonStreamReaderTest`, 18 `XtreamParserTest`, 6 `XtreamClientTest`).
- 30 000 films (`get_vod_streams` généré, ≈ 8,5 Mo de JSON) parsés en flux en **0,44 s** sur le Mac ; identifiants uniques, catégories et années conservés.
- `HttpXtreamClient` exercé contre un serveur local `ServerSocket` : identifiants transmis uniquement en paramètres de requête, redirection suivie en gardant la requête, page HTML et HTTP 503 traduits en français, hôte injoignable sans écho de l'adresse.
- Aucun message d'erreur (refus, expiration, HTTP 401/403/404/429/5xx, hôte introuvable, URL malformée, `IOException` brute, exception inconnue) ne contient le serveur, l'identifiant ou le mot de passe.
- Lint : 29 avertissements, aucun nouveau, aucune erreur. Schéma Room inchangé.

Écarts par rapport au plan, consignés dans `SPRINT3.md` : lecteur JSON maison au lieu d'`android.util.JsonReader` (stub dans les tests locaux), et référence `xtream://` stockée dans `Channel.streamUrl` pour que la table `channels` ne contienne jamais d'identifiant.

## Sprint 3 — lot 2 : identifiants chiffrés et persistance Room v3 (29 octobre 2026)

Toujours sans réseau ni compte : faux client Xtream (`FakeXtreamClient`, réponses enregistrées passées dans les vrais parseurs) et clé AES générée sur place à la place du Keystore.

- Unitaires : **74/74** (9 nouveaux : 3 `AesGcmCipherTest`, 4 `MemoryXtreamRepositoryTest`, 2 `XtreamViewModelTest`).
- Instrumentés : **22/22** sur l'émulateur Pixel 2 API 35 (`CatalogPerformanceTest` ignoré sans `-e perf true`), dont 6 `XtreamRepositoryTest` et `MigrationTest` v1 → v2 → v3. Première passe : un échec dû au test lui-même (une catégorie Xtream sans chaîne est purgée par Room comme un groupe M3U vide) ; attente corrigée, 7/7 à la relance des deux classes Room.
- Vérifié en base : la colonne `encryptedPassword` commence par `v1:` et ne contient pas le mot de passe ; `playbackUrl` reconstruit `…/live/u-test/p-test/101.ts` ; clé perdue → actualisation en échec « Identifiants à ressaisir. », catalogue conservé, `playbackUrl` nul ; compte refusé ou expiré à l'actualisation → erreur sur la source, catalogue conservé ; fiches film et série demandées une fois puis servies du cache, cache vidé par l'actualisation ; favoris et positions de reprise conservés à l'actualisation, supprimés avec la source ; après suppression, les dix tables sont vides.
- Migration : les instructions de `MIGRATION_2_3` sont celles du schéma exporté `3.json` (vérifié par script) ; `MigrationTestHelper` valide colonnes, valeurs par défaut (`movieCount`, `seriesCount` à 0) et index.
- Lint : **28 avertissements** (29 avant : `DataExtractionRules` résolu par les règles de sauvegarde), aucune erreur.

Non vérifié ici : le Keystore réel (clé matérielle, perte après restauration) et l'exclusion effective du transfert d'appareil à appareil, qui demandent un appareil et un compte ; prévus avec la validation du lot 3 sur le Xiaomi 15.

## Sprint 3 — lot 3 : interface Xtream, Films, Séries, lecteur VOD (29 octobre 2026)

Sans réseau ni compte : faux client Xtream et dépôt mémoire derrière la vraie interface Compose.

- Unitaires : **80/80** (6 nouveaux dans `XtreamViewModelTest`, 8 au total : ajout, formulaire incomplet ou refusé, test de connexion, catégories et filtres restaurés, fiche film et reprise, fiche série avec saisons et « Épisode suivant », recherche groupée, lecteur unique et fermeture des fiches avec leur source).
- Instrumentés : **26/26** sur l'émulateur Pixel 2 API 35 (`CatalogPerformanceTest` ignoré sans `-e perf true`), dont 4 `XtreamPhoneTest` : formulaire Xtream avec test de connexion refusé puis accepté, ajout et fiche source (expiration), mot de passe absent de l'écran ; grille Films → fiche (synopsis, durée) → « Lire » → lecteur → plein écran → fermeture ; fiche Séries → saison 2 → saison 1 → épisode → « Épisode suivant » ; rangée « Reprendre » de l'Accueil → fiche avec « Reprendre à 42 min ». Première passe : un échec dans un test du Sprint 2 (« Aucune source » passé sous le pli par le nouveau choix M3U / Xtream) ; défilement ajouté, 6/6 à la relance.
- Incident de développement : une première version des tests ViewModel lisait les listes paginées `cachedIn` du ViewModel sous `runBlocking`, ce qui bloque avec le `StandardTestDispatcher` ; les tests lisent désormais le dépôt pour les requêtes du modèle, comme ceux du Sprint 2.
- Lint : **28 avertissements**, aucun nouveau, aucune erreur. Schéma Room inchangé (v3).

Non vérifié ici, à faire sur le Xiaomi 15 avec un compte de test : lecture réelle d'un film et d'un épisode Xtream (résolution de l'URL, saut ±10 s, reprise après fermeture), « Épisode suivant » à la fin réelle d'un épisode, affiches Coil sur un vrai catalogue, Keystore réel, fluidité des grilles sur plusieurs milliers de films.

## Sprint 3 — lot 4 : tests, CI et documentation (29 octobre 2026)

- Tests ajoutés : `FormatTest` (6 unitaires : durées et « Reprendre à », sous-titre de film, titres d'épisode et épisode suivant, seuil « Vu » à 95 %, filtre VOD insensible aux accents, description du compte) et `PlayerResolutionTest` (instrumenté : identifiants absents → « Identifiants de la source à ressaisir dans Sources » avec « Réessayer », nouvel essai qui résout à nouveau la référence, URL résolue atteignant un serveur local muet puis délai borné, fermeture du lecteur).
- Couverture du plan vérifiée : parseurs, URL, messages, chiffrement, ViewModel Films et Séries (unitaires) ; migration 2 → 3, ajout Xtream par faux client, Films → fiche → lecture, Séries → saison → épisode, reprise (instrumentés). Non automatisable : la fin réelle d'un épisode et la sauvegarde de position par le vrai lecteur, qui demandent un média lisible ; couvertes par les tests du ViewModel et à observer sur appareil.
- CI inchangée (`android.yml`) : unitaires, lint, APK et APK de test, vérification du schéma exporté, émulateur Pixel 2 API 35. Les fixtures partagées (`src/sharedTest`) sont compilées dans les deux source sets par Gradle, sans étape supplémentaire.
- Unitaires : **86/86**. Instrumentés : **27/27** (`CatalogPerformanceTest` ignoré sans `-e perf true`). Lint : 28 avertissements, aucune erreur.
- `README.md` réécrit pour le Sprint 3 (fonctionnalités Xtream, architecture, choix de conception, sécurité, tests) ; ce fichier réorganisé par lot avec la synthèse ci-dessus.

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
