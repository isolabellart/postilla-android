# Postilla — procedura ufficiale di release

Questa è la procedura standard per tutte le versioni future di Postilla. La pubblicazione Android su GitHub e la pubblicazione Zapstore sono fasi distinte, collegate automaticamente dall'evento GitHub `release.published`.

## 1. Regole che non si derogano

- Non pubblicare mai da `main` e non cambiare il branch predefinito del repository come parte di una release.
- La sorgente applicativa deve essere una staging già validata dalla CI e collaudata manualmente sul dispositivo.
- Prima di preparare una release creare un branch Git di backup verificabile e annotare SHA del backup, branch sorgente e commit approvato.
- Non modificare né rigenerare il keystore, l'alias, le password o la firma Android.
- Non riutilizzare un `versionCode`, un tag Git o una release già pubblicati.
- Non ricostruire o sovrascrivere una release GitHub per risolvere un errore Zapstore.
- Non considerare la sola conclusione positiva di `zsp publish` una prova di pubblicazione: la conferma finale è `published_version_code` uguale al `versionCode` dell'APK, con hash APK e certificato attesi.

## 2. Pubblicazione Android su GitHub

Il workflow Android ufficiale resta versionato per ogni release, perché contiene i riferimenti immutabili alla staging approvata, al commit precedente, al numero versione e alle note di rilascio.

Per una nuova versione:

1. Completare la staging `X.Y.Z`: test automatici verdi e collaudo manuale approvato.
2. Creare un backup Git e un branch `release/postilla-vX.Y.Z` dal commit staging approvato.
3. Preparare il workflow `.github/workflows/postilla-vX.Y.Z-release.yml` partendo dal workflow della release precedente.
4. Aggiornare con attenzione: nome workflow, branch trigger, commit staging approvato, commit release precedente, versione, `versionCode`, nome APK e note di rilascio. Mantenere i controlli su package, firma permanente e tag già esistente.
5. Il workflow deve eseguire test, build Release firmata, verifica package/versione/fingerprint, calcolo SHA-256 e upload dell'APK. Deve creare la release GitHub come draft, caricare l'APK esatta e pubblicare la release solo dopo aver verificato l'asset.
6. Avviare il workflow dal branch release e attendere il completamento. Se fallisce prima della pubblicazione GitHub, correggere la causa sul branch release senza alterare staging o firma.

Nome asset obbligatorio: `Postilla-vX.Y.Z.apk`.

Il workflow Android non deve contenere credenziali Zapstore né eseguire direttamente `zsp publish`.

## 3. Pubblicazione Zapstore automatica

Il workflow permanente è:

`.github/workflows/zapstore-publish.yml`

Deve rimanere sul branch predefinito del repository, attualmente `feature/postilla-commerce-on-rebrand`. GitHub carica i workflow associati all'evento release dal branch predefinito: aggiungerlo soltanto a un branch release o diagnostico non è sufficiente.

Trigger ordinario: `release: published`. Quando il workflow Android rende pubblica la release GitHub, GitHub avvia automaticamente la pubblicazione Zapstore.

Il workflow Zapstore:

1. accetta solo tag stabili `vX.Y.Z` e verifica che la release GitHub sia già pubblicata, non draft e non prerelease;
2. richiede l'asset esatto `Postilla-vX.Y.Z.apk`;
3. usa una configurazione temporanea con `release_filter` limitato al solo tag richiesto, senza modificare `zapstore.yaml`;
4. esegue `zsp check` e verifica package Android, versione, `versionCode`, hash APK e fingerprint del certificato permanente;
5. se la stessa versione è già indicizzata, non la ripubblica; se Zapstore ha già una versione più nuova, rifiuta il downgrade;
6. se serve pubblicare, installa la chiave client Bunker in una directory privata del runner e verifica l'identità effettiva tramite handshake NIP-46;
7. pubblica con `zsp publish` soltanto dopo che la pubkey delegata restituita dall'handshake corrisponde all'identità Postilla attesa;
8. ripete il controllo del catalogo fino a 10 minuti, richiedendo che `published_version_code`, hash APK e fingerprint coincidano con la release richiesta;
9. conserva i risultati JSON e gli errori come artifact diagnostico in caso di necessità.

### Identità e credenziali Zapstore

Secret GitHub Actions richiesti:

- `ZAPSTORE_SIGN_WITH`: URI Bunker del remote signer dedicato a Postilla.
- `ZAPSTORE_BUNKER_CLIENT_KEY`: chiave client NIP-46 esadecimale a 64 caratteri.

La pubkey del remote signer contenuta nell'URI Bunker e la pubkey delegata restituita da NIP-46 sono identità diverse e non vanno confrontate tra loro. La chiave client viene salvata sul runner sotto `~/.config/zsp/bunker-keys/` con permessi restrittivi e non viene mai stampata nei log.

Pubkey delegata Postilla attesa:

`8af9683c5868749cc3a261a0e006f3f53e4bea0aa81f31f0f027d684e7f0c0c8`

Fingerprint SHA-256 del certificato Android permanente:

`86868a3659729a6531f3e8a31d5dcfe04405e2a86866f3e0eb0abb6965cdb841`

CLI Zapstore fissata alla versione `zsp v2.0.1`; toolchain Go fissata a `1.27.3`, già verificata nella procedura di controllo catalogo.

## 4. Recupero in caso di errore

### GitHub release pubblicata, Zapstore workflow fallito

Non rilanciare il workflow Android, non ricreare il tag e non caricare nuovamente l'APK.

1. Aprire la run fallita di **Publish and verify Postilla release on Zapstore**.
2. Leggere il primo errore concreto e l'artifact `zapstore-release-diagnostics`.
3. Se la causa è temporanea e i controlli sono corretti, avviare manualmente lo stesso workflow con **Run workflow**, inserendo il tag già pubblicato, per esempio `v1.0.34`.
4. Il preflight impedisce una pubblicazione duplicata se la versione è già indicizzata e impedisce il downgrade se nel frattempo è stata pubblicata una versione più nuova.

### Pubblicazione accettata ma catalogo non aggiornato

Il comando `zsp publish` può restituire `status: pending`. La run non è conclusa con successo finché il controllo successivo non legge dal catalogo il `published_version_code` atteso e gli stessi hash dell'APK. Se il polling scade, conservare i log e gli artifact; non avviare pubblicazioni ripetute alla cieca.

### Release GitHub non pubblicata

Se il workflow Android fallisce prima che la release GitHub diventi pubblica, Zapstore non deve partire. Correggere e ripetere solo la fase GitHub, mantenendo il controllo che impedisce di sovrascrivere tag/release già esistenti.

## 5. Verifica conclusiva da annotare

Per ogni versione registrare:

- branch, commit staging approvato e commit release;
- nome e SHA del branch backup;
- run GitHub Android e relativo esito;
- URL release GitHub e nome asset;
- SHA-256 dell'APK e fingerprint certificato;
- run Zapstore e relativo esito;
- `versionCode` restituito dal catalogo Zapstore;
- eventuali retry manuali e motivazione.

Una release è completa soltanto quando GitHub contiene la release stabile con l'APK firmata corretta e Zapstore conferma nel catalogo la stessa versione e lo stesso hash.
