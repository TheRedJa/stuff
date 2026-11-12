# TPA & Homes – Fabric 26.3 (nur Server)

Server-Mod für Minecraft **26.3** mit Fabric. Spieler brauchen die Mod **nicht** auf ihrem Client.

**Fertige Jar:** [`release/tpa-homes-1.0.0.jar`](release/tpa-homes-1.0.0.jar)

## Installation

1. Fabric-Server für 26.3 (Fabric Loader ≥ 0.20.1, **Java 25**).
2. In den Ordner `mods/` kopieren:
   - `tpa-homes-1.0.0.jar`
   - [Fabric API](https://modrinth.com/mod/fabric-api) für 26.3 (getestet mit `0.166.2+26.3`)
3. Server starten.

## Befehle (jeder Spieler darf alle benutzen, kein OP nötig)

| Befehl | Beschreibung |
|---|---|
| `/tpa <Spieler>` | Teleport-Anfrage an einen Spieler (Alias: `/tps <Spieler>`). Cooldown: **15 s**. |
| `/tpaccept [Spieler]` | Anfrage annehmen (Alias: `/tpaaccept`). Ohne Namen wird die neueste angenommen. Alternativ im Chat auf **[Annehmen]** klicken. |
| `/tpadeny [Spieler]` | Anfrage ablehnen (Alias: `/tpdeny`) oder auf **[Ablehnen]** klicken. |
| `/tpacancel` | Eigene offene Anfrage zurückziehen. |
| `/sethome <Name>` | Home an der aktuellen Position setzen (bzw. überschreiben). Max. **10** Homes. |
| `/home <Name>` | Zum Home teleportieren, Tab-Vorschläge zeigen deine Homes. Cooldown: **30 s**. |
| `/home` oder `/homes` | Liste deiner Homes (anklickbar). |
| `/delhome <Name>` | Home löschen. |

### Ablauf TPA
1. Spieler A: `/tpa B` → B bekommt eine Chat-Nachricht mit den Buttons **[Annehmen]** / **[Ablehnen]**.
2. B nimmt an → **beide müssen 5 Sekunden stillstehen** (Countdown in der Actionbar; Umschauen ist erlaubt).
3. Bewegt sich einer der beiden (> 0,2 Blöcke), stirbt oder geht offline, wird der Teleport abgebrochen.
4. Danach wird A zu B teleportiert. Offene Anfragen verfallen nach 60 Sekunden.

## Konfiguration

Wird beim ersten Start unter `config/tpahomes.json` angelegt:

```json
{
  "maxHomes": 10,
  "tpaCooldownSeconds": 15,
  "homeCooldownSeconds": 30,
  "tpaWarmupSeconds": 5,
  "tpaRequestTimeoutSeconds": 60,
  "tpaMaxMoveDistance": 0.2
}
```

Homes werden pro Welt in `world/tpahomes_homes.json` gespeichert.

## Selbst bauen & testen

```bash
./gradlew build         # Jar landet in build/libs/
./gradlew runGameTest   # automatisierte Tests mit simulierten Spielern
```
