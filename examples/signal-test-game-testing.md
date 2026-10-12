# Das einfache Signal-Test-Spiel prüfen

Importiere `signal-test-game.md` neu und starte **Signal-Test: Der Leuchtturm
von Nebelhafen** als neues Spiel. Das getrennte `test-game.md` bleibt das
komplexe Beispiel für bedingte Runtime-Fragmente und ältere Signalzuordnungen.
Öffne das Debug-Panel; anfangs sind alle drei Flags `false`.

Die Aktionen unten sind Anregungen. Entscheidend ist das tatsächlich erzählte
Ereignis im abgeschlossenen Austausch, nicht die Absicht im Aktionsfeld oder
welche Anleitung die Spielleitung abgerufen hat. Eine verschlossene Tür und ein
fehlgeschlagener Eintritt dürfen das Besuchsflag nicht setzen. Eine Suche ohne
konkreten Fund darf das Hinweisflag nicht setzen. Ebenso zählt erst die erzählte
Reaktion der Hafenmeisterin, nicht allein ein Vorwurf oder eine Entschuldigung.

| Aktion / erzähltes Ergebnis | Ereignisprüfung | Erwarteter Flag-Zustand |
| --- | --- | --- |
| „Ich betrete den Leuchtturm.“ / tatsächlicher Eintritt | `betritt-leuchtturm` → `Y` | `war-im-leuchtturm: true` |
| „Ich untersuche die Spuren.“ / konkreter Hinweis zum Hafenfall | `entdeckt-hinweis` → `Y` | `hat-hinweis: true` |
| „Deine Geschichte ist widersprüchlich!“ / Hafenmeisterin wird misstrauisch | `verunsichert-hafenmeisterin` → `Y` | `hafenmeisterin-misstrauisch: true` |
| „Es tut mir leid, ich möchte helfen.“ / Hafenmeisterin beruhigt sich | `beruhigt-hafenmeisterin` → `Y` | `hafenmeisterin-misstrauisch: false` |

Prüfe im Debug-Panel die einzelnen Ereignisprüfungen: Beschreibung, Antwort
`Y` oder `N` und Dauer/Token-/Cache-Metriken pro Modellaufruf, soweit geliefert.
Die angewendeten Flag-Änderungen müssen zur Antwort passen. Nur die gelieferten
`tokens_cached` und `tokens_evaluated` belegen Cache-Wiederverwendung; Dauer
allein genügt nicht. Ereignisprüfungen kommen nach Fokus-/Rerank-Auswahl und
abgeschlossenem Austausch. Ein früh ausgewähltes Fragment ist kein Signalbeleg;
auch ein Ereignis ohne ausgewähltes passendes Fragment kann erkannt werden.

Die Signalbedingungen entscheiden anhand der Flags zu Beginn des Zuges über
die Berechtigung zur Prüfung. Die zwei Fortschrittsereignisse werden nur bei
ihrem jeweiligen Flag `false` geprüft. Nach einem Treffer bleiben sie `true`,
auch nach Verlassen des Turms und nach Neuladen/Fortsetzen derselben Sitzung.
Wiederholtes Betreten oder erneuter Fund erzeugt für diese bereits gesetzten
Flags keine weitere berechtigte Prüfung und keine erneute Änderung.

Misstrauen lässt sich dagegen mehrfach umschalten: Bei `false` ist nur
`verunsichert-hafenmeisterin` berechtigt, bei `true` nur
`beruhigt-hafenmeisterin`. Wiederhole Vorwurf und Versöhnung mit tatsächlich
erzählter Reaktion. Ein Treffer löst im selben Zug keine Folgeprüfung des gerade
erst berechtigten Gegenereignisses aus. Auch dieses Flag muss beim Fortsetzen
seinen letzten Zustand behalten.

Für negative Gegenchecks starte ein weiteres neues Spiel. Gehe nur zum Turm
und betrachte ihn von außen: Das Besuchsflag bleibt `false`, die berechtigte
Eintrittsprüfung sollte `N` ergeben. Führe ein neutrales Gespräch mit der
Hafenmeisterin: Ihr Misstrauensflag bleibt `false`. Prüfe außerdem eine bloße
Suchabsicht bzw. einen erzählten erfolglosen Versuch ohne Fund. Hinweise dürfen
auch außerhalb des Turms gefunden werden; ihre Prüfung hängt nicht vom Besuch
ab. Nicht berechtigte Prüfungen werden ausgelassen, sie sind kein `N`-Ergebnis.

Die Zuordnung erfolgt durch das lokale Modell. Diese Anleitung prüft dessen
beobachtete Antworten und Zustandsänderungen; sie garantiert keine bestimmte
Erzählung. Der automatisierte Parser-Test lädt die echten Beispieldateien und
prüft ihre Struktur, nicht die Qualität oder Ereignistreue einer Modellantwort.
