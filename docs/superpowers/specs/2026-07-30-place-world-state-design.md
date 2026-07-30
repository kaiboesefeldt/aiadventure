# Ortsbezogener WorldState

Der persistierte WorldState besteht ausschließlich aus `location: String` und ist leer, solange kein Ort kanonisch festgestellt wurde. Die alte generische Entitaets- und Faktenkartei entfällt vollstaendig. Szenarien initialisieren ihn mit `world-state.location`.

Nach einem abgeschlossenen sichtbaren Turn fragt das Secondary-Modell zuerst mit festen Beispielen, ob die Spielerfigur den Szenenort wirklich verlassen hat. Es darf nur einen letzten `FINAL: move` oder `FINAL: stay` liefern. Bei `stay`, fehlendem oder mehrdeutigem Ergebnis bleibt der State unverändert.

Bei `move` fragt ein zweiter Prompt nach dem Zielort. Der Server übernimmt nur den letzten `FINAL: <name>`, wenn der normalisierte Name als vollstaendiger Textbestandteil in der Spielleiter-Antwort vorkommt. Das erlaubt neue Orte ohne Modell-IDs; bekannte und neue Namen werden gleich behandelt. Der alte Ort wird keiner LLM-Abfrage vorgelegt.

Die feste Prompt-Anleitung und Beispiele bilden den gecachten Prefix. Die Begruendung wird nie geloggt und nicht persistiert. Der Server bleibt zustandslos; der Browser persistiert den `location`-State wie bisher.
