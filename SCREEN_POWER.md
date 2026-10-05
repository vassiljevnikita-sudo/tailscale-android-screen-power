# Tailscale Screen — experimenteller Android-Build

Dieser Fork ergänzt einen standardmäßig eingeschalteten, abschaltbaren Bildschirm-Sparmodus. Er ist kein offizieller Tailscale-Release. Basis: `tailscale/tailscale-android` Commit `6d63d3e29a1d1f9710f78863a91cc76b39ea2f9a` und Tailscale-Core `689a0c155f57857f9760c7e01ca3718815fe5a59` (Entwicklungsstand 1.105.4).

## Verhalten

- Nach einmaligem Anmelden und Verbinden: Bildschirm aus setzt ausschließlich `WantRunning=false`; Bildschirm an setzt es wieder auf `true`.
- Der bestehende Tailscale-Core übernimmt Tunnelabbau und Wiederherstellung. Login, Schlüssel, DNS-, Routing-, Split-Tunneling-, Exit-Node- und Tailnet-Lock-Konfiguration bleiben erhalten.
- Ein Vordergrunddienst bleibt für Android-Bildschirmereignisse aktiv. Keine neue regelmäßige Abfrage, kein Alarm, kein Wake-Lock.
- Manuelles Trennen hebt die automatische Wiederverbindung auf. Der Verbindungswunsch wird unabhängig von der vorübergehend pausierten Backend-Einstellung gespeichert, damit ein Prozessneustart ihn erhalten kann.
- VPN-Neustarts nach Einstellungsänderungen bauen den Tunnel im vorhandenen Dienst neu auf, statt den Bildschirm-Empfänger durch einen Dienstneustart zu verlieren.
- Schnelle Bildschirmwechsel werden zusammengefasst; Backend-Änderungen werden seriell ausgeführt. Vor dem Dienstende wird ein während einer laufenden Anfrage eingegangener Trennwunsch erneut abgeglichen.
- Die Einstellung „Pause when screen is off“ deaktiviert die Automatik und stellt den ursprünglichen Hintergrundbetrieb wieder her.
- Androids VPN-Sperrmodus (Always-on mit „Verbindungen ohne VPN blockieren“) und erzwungene MDM-Verbindung haben Vorrang; dort wird nicht pausiert.

Während der Pause ist das Handy nicht im Tailnet erreichbar. MagicDNS, Tailnet-Routen, Exit-Node-Traffic und Taildrop stehen dann nicht bereit; laufende Verbindungen und Dateiübertragungen können abbrechen. Internetverkehr geht bei aufgehobenem VPN normal über WLAN/Mobilfunk. Beim Einschalten erfolgt ein neuer Verbindungsaufbau und keine garantiert verzögerungsfreie Verbindung.

## Ergebnis der Codeprüfung

Der ursprüngliche Android-Dienst startet das VPN dauerhaft und kennt keine Display-Pause. Im Core werden beim Übergang zu `Stopped` die Engine und deren Routing/DNS-Konfiguration geleert (`ipn/ipnlocal/local.go`, `enterStateLocked`). Bei vorhandenem Netmap pausiert der Kontrollclient (`shouldPauseControlClientLocked`). Das Leeren des aktiven WireGuard-Schlüssels schließt DERP-Verbindungen, stoppt den periodischen STUN-Timer und setzt Peer-Endpunkte zurück (`wgengine/magicsock/magicsock.go`, `SetPrivateKey`). Auf Android schließt eine leere Router-Konfiguration die TUN-Geräte (`libtailscale/net.go`, `updateTUN`). Persistierte Identitätsschlüssel werden dabei nicht gelöscht.

Der Core begrenzt periodisches STUN bereits bei Inaktivität. Häufige Keepalives sind deshalb keine ohne Messung belegte Ursache des konkreten Verbrauchs. Android-Netzwerkinterfaces werden in diesem Stand über Android/Java als JSON bereitgestellt; der alte Fehler mit wiederholt gestarteten `ip`-Prozessen wurde hier nicht als aktueller Hotspot gefunden. Diagnose-Logging und Netzwerkbeobachtung bleiben erhalten und können auch während der Pause Restverbrauch erzeugen.

Der größte konkrete Hebel für den angefragten Nutzungsfall ist das Abschalten der Hintergrundverbindung. Eine pauschale Optimierung von Verschlüsselung, Handshake-Intervallen oder Paketverarbeitung um mehr als 50 % wurde nicht nachgewiesen und nicht vorgenommen.

## Akku-Einsparung

**Keine gemessene 50-%-Garantie.** Das bereitgestellte Bild zeigt 4,66 % Tailscale-Akkuverbrauch und 12 h 41 min Hintergrundbetrieb. Daraus lassen sich weder Bildschirm-aus-Anteil noch Funk-, CPU- oder Wake-Lock-Kosten ableiten.

Bei dominierendem Bildschirm-aus-Betrieb ist mehr als 50 % weniger Tailscale-bedingter Verbrauch plausibel. Gemeint ist der Verbrauch dieser App, nicht die Akkulaufzeit des gesamten Handys. Wiederverbindungen benötigen zusätzliche Energie. Für einen Beleg müssen Original und Fork auf demselben Handy mit vergleichbarer Nutzung gemessen werden.

## Installation und Vergleich

1. Die signierte experimentelle APK herunterladen und installieren. Paketname: `com.tailscale.ipn.screenpower`; sichtbarer Name: „Tailscale Screen“. Android 8+; alle vier von Upstream gebauten Architekturen sind enthalten.
2. Original-Tailscale trennen und dessen Always-on-VPN deaktivieren. Android kann nur einen regulären VPN-Dienst gleichzeitig verwenden. Die Original-App kann installiert bleiben.
3. In Tailscale Screen neu anmelden und die VPN-Berechtigung erteilen. Wegen separatem Paket und separater Signatur werden die gespeicherten Anmeldedaten der Original-App nicht übernommen.
4. Einmal verbinden. Der Sparmodus ist bereits eingeschaltet. Die permanente Statusmeldung erhalten; die App nicht in Samsungs Tiefschlafliste aufnehmen, wenn automatische Wiederverbindung gewünscht ist.
5. Vor dem Akkuvergleich Bildschirm aus/an, manuelles Trennen, WLAN/Mobilfunkwechsel, MagicDNS, verwendete Subnet-Routen und gegebenenfalls Exit Node prüfen. Beim Einschalten eine kurze Wiederverbindungszeit einplanen.
6. Je einen vergleichbaren Tag Original und Fork verwenden: gleicher Ladezustand, ähnliche Displayzeit, Empfang, Datenmenge und Exit-Node-Konfiguration. Samsung-Statistik und möglichst Android-Batterystats vergleichen. Eine Halbierung von 4,66 auf 2,33 in derselben Statistik wäre nur bei vergleichbaren Messbedingungen aussagekräftig.

## Validierung und Grenzen

- Release-APK gebaut, R8 aktiviert; APK-Signatur wird separat überprüft.
- 74 bestandene JVM-Unit-Tests (0 Fehler, 0 übersprungen), darunter neue Tests für schnelle Displaywechsel, Trennen während laufendem Connect, Backend-Fehler, Policy-Vorrang und abschaltbaren Sparmodus.
- `ktfmtFormat`, `test`, `assembleRelease`, `lintVitalRelease`.
- Kein Test im echten Tailnet, keine Hardware-Akkumessung, kein Test auf dem Handy des Nutzers. Der T3-Gerätehost meldet Android als nicht verfügbar; deshalb keine bestätigte Android-Laufzeitprüfung.
- Systembedingtes Force-Stop, Hersteller-Tiefschlaf oder das vollständige Beenden der App können automatische Bildschirmereignisse verhindern. Keine Zusage einer dauerhaft erreichbaren Verbindung.

## Selbst bauen

Upstream-Anleitung in README.md befolgen. `make androidsdk` und `make apk` bauen einen lokal signierten Debug-Build; `cd android && ./gradlew test assembleRelease lintVitalRelease` baut den optimierten Release-Build (benötigt zuvor die native AAR und `tailscale.version`). Die lokal gebaute Release-APK nutzt als Fallback den lokalen Android-Debug-Schlüssel. Die veröffentlichte APK wird anschließend mit einem eigenen, nicht veröffentlichten Schlüssel signiert. Ein selbst gebauter Build kann sie daher nicht ohne Deinstallation ersetzen.
