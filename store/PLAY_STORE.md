# Publier miptv sur Google Play

Guide de la première publication (version 1.0.0, `versionCode` 6). Les textes sont prêts à copier dans la Play Console.

## 1. Compiler le bundle

Google Play n'accepte que des Android App Bundles (`.aab`). Le build `release` est minifié par R8 et signé avec la clé `miptv.jks` décrite dans le README (`keystore.properties` ou variables `MIPTV_*`).

```sh
./gradlew bundleRelease
```

Fichiers à envoyer :

- `app/build/outputs/bundle/release/app-release.aab` : le bundle ;
- `app/build/outputs/mapping/release/mapping.txt` : à joindre à la version (*Explorateur d'App Bundle → Téléchargements*) pour lire les plantages R8 en clair. La Console le récupère d'ordinaire depuis le bundle.

**Signature** : accepter la *signature d'application par Google Play* (Play App Signing). Google garde la clé de signature de l'application ; `miptv.jks` devient seulement la **clé d'importation**. Perdue, elle se réinitialise depuis la Console. Conséquence : l'application installée depuis Play n'a pas la même signature que l'APK de debug ; passer de l'une à l'autre demande de désinstaller.

À chaque nouvelle version, incrémenter `versionCode` (et `versionName`) dans `app/build.gradle.kts`.

## 2. Compte développeur

- Compte Google Play Console (25 $ une fois) et vérification d'identité du développeur.
- **Compte personnel créé après le 13 novembre 2023** : avant la production, un **test fermé avec au moins 12 testeurs inscrits pendant 14 jours consécutifs** est obligatoire. Prévoir ces testeurs (adresses Gmail) dès maintenant. Un compte d'organisation n'a pas cette contrainte.

## 3. Créer l'application

- Nom : `miptv` · Langue par défaut : Français (France) · Type : Application · Gratuite.
- Le nom de package `fr.manitra.miptv` (déjà créé dans la Console) est définitif.

## 4. Fiche du Store

**Nom** (30 car. max) : `miptv`

**Description courte** (80 car. max) :

> Lecteur IPTV pour vos playlists M3U et comptes Xtream : TV, films et séries.

**Description complète** :

> miptv est un lecteur IPTV pour smartphone Android. Ajoutez vos propres sources et retrouvez la TV en direct, les films et les séries dans une seule application.
>
> ⚠️ miptv ne fournit aucun contenu. Aucune chaîne, playlist ni abonnement n'est inclus ou vendu. Vous devez disposer de vos propres sources, et de l'autorisation de les regarder.
>
> SOURCES
> • Playlists M3U/M3U8 par adresse ou fichier local
> • Comptes Xtream Codes (serveur, identifiant, mot de passe), avec état du compte et date d'expiration
> • Guide des programmes XMLTV
>
> FONCTIONNALITÉS
> • TV en direct par catégories, avec programme en cours et guide
> • Films et séries avec fiches, saisons et épisodes
> • Reprise de lecture et « Continuer le visionnage »
> • Favoris conservés après actualisation des sources
> • Recherche dans tout le catalogue
> • Lecture en plein écran, écran maintenu allumé pendant la lecture
> • Grandes playlists : des dizaines de milliers de chaînes sans ralentissement
>
> CONFIDENTIALITÉ
> • Aucun compte, aucune publicité, aucun suivi
> • Tout reste sur votre téléphone ; les mots de passe sont chiffrés

**Graphismes** (dans `store/graphics/`) :

- Icône 512 × 512 : `icon-512.png`
- Image de présentation 1024 × 500 : `feature-1024x500.png`
- Captures d'écran téléphone : **2 minimum** (8 max), format 9:16, 1080 px de large conseillés. À prendre sur l'émulateur avec une playlist de démonstration **sans aucune chaîne, logo ou affiche de marque réelle** (TF1, beIN, Netflix, affiches de films…) : c'est le premier motif de refus des lecteurs IPTV. Écrans conseillés : Accueil vide, Sources, liste de chaînes de test, guide, fiche film.

**Catégorie** : Lecteurs et éditeurs vidéo · **Coordonnées** : adresse e-mail de contact (obligatoire, publique).

## 5. Contenu de l'application (*Règles et programmes → Contenu de l'application*)

| Section | Réponse |
|---|---|
| Règles de confidentialité | URL publique de `store/PRIVACY.md`, par ex. `https://github.com/rmhambatofi/miptv/blob/main/store/PRIVACY.md` (une fois fusionné dans `main`, après y avoir mis l'adresse de contact) |
| Accès aux applications | Toutes les fonctionnalités sont disponibles sans restrictions. **Mais** sans source, l'app est vide : ajouter en note une playlist de test légale accessible au relecteur (voir ci-dessous) |
| Annonces | Non, l'application ne contient pas d'annonces |
| Classification du contenu | Questionnaire IARC, catégorie « Toutes les autres applications ». Répondre Non à violence, sexualité, etc., mais **Oui à « contenu généré ou fourni par l'utilisateur / accès non filtré à Internet »** si demandé : l'app lit des flux arbitraires |
| Public cible | 18 ans et plus (ou 13+) ; ne pas cibler les enfants |
| Sécurité des données | Voir ci-dessous |
| Applis gouvernementales, financières, santé, actualités | Non |

### Sécurité des données

- *Votre application collecte-t-elle ou partage-t-elle des types de données utilisateur requis ?* → **Non**.

Justification : rien n'est envoyé au développeur ni à un tiers choisi par le développeur. Les identifiants Xtream ne sont transmis qu'au serveur que l'utilisateur a lui-même saisi, ce que Google exclut de la « collecte » (transfert initié par l'utilisateur vers le service qu'il a choisi). Pas de SDK d'analyse, de publicité ni de crash reporting dans les dépendances.

- Les données sont-elles chiffrées en transit ? Ne pas cocher de garantie : l'application accepte les sources `http://` (`usesCleartextTraffic`).

### Playlist pour le relecteur

Le relecteur Google doit pouvoir voir l'app fonctionner. Fournir dans *Accès aux applications* une adresse de playlist M3U **légale et publique** (par exemple un fichier hébergé dans ce dépôt pointant vers des flux de test libres comme les flux de démonstration HLS d'Apple ou de Mux). Ne jamais fournir de playlist de chaînes protégées.

## 6. Risque de refus propre aux lecteurs IPTV

Google retire régulièrement des lecteurs IPTV au titre de la règle *Propriété intellectuelle*. Ce qui protège miptv :

- l'app ne contient, ne suggère ni ne vend aucun contenu ou fournisseur (déjà le cas dans le code) ;
- l'avertissement « miptv ne fournit aucun contenu » est en tête de la description ;
- aucune marque, chaîne ou affiche réelle dans les captures, l'icône ou le texte ;
- ne pas utiliser de mots-clés comme « chaînes gratuites », « beIN », « Netflix », « foot en direct ».

En cas de refus, la Console indique la règle invoquée ; on peut faire appel en expliquant que l'app est un lecteur sans contenu, comme VLC.

## 7. Parcours de publication

1. *Tests → Test interne* : envoyer l'AAB, s'ajouter comme testeur, installer depuis le lien et vérifier (ajout de source, lecture, guide, favoris).
2. *Tests → Test fermé* : même bundle, 12 testeurs minimum pendant 14 jours (comptes personnels récents).
3. *Production* : demander l'accès à la production (questionnaire sur le test fermé), puis publier, de préférence en déploiement progressif (20 %, puis 100 %).
4. Pays : tous, ou commencer par la France / Madagascar / pays francophones (l'interface est en français uniquement).

## Checklist

- [ ] Adresse de contact ajoutée dans `store/PRIVACY.md`, fusionné dans `main`
- [ ] Compte Play Console vérifié
- [ ] AAB `bundleRelease` construit avec `miptv.jks`, Play App Signing accepté
- [ ] Icône, image de présentation, 2 à 8 captures sans marque réelle
- [ ] Description courte et complète
- [ ] Règles de confidentialité, accès aux applis (+ playlist de test), annonces, classification, public cible, sécurité des données
- [ ] Test interne validé sur un vrai téléphone
- [ ] Test fermé 12 testeurs × 14 jours (si compte personnel)
- [ ] Production
