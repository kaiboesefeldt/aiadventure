# Weltzustand

## Ziel

Eine Browser-Session besitzt einen kleinen, persistierten und versionsfähigen Weltzustand. Er gibt dem Primary-Modell vor jeder Spieleraktion eine kompakte, verständliche Ausgangslage. Ein separates Secondary-Modell wertet nach der sichtbaren Antwort den vorherigen Zustand, die Aktion und die Antwort aus und schlägt einen JSON-Patch vor.

## Vertrag

- Der kanonische Zustand besteht aus einer Entitätskartei und aktuellen Fakten. Entitäten können während des Spiels entstehen; IDs sind intern, Namen und Kurzbeschreibungen stehen im Prompt.
- Das Secondary-Modell erzeugt ausschließlich einen begrenzten Patch. JSON-Schema/GBNF erzwingt dessen Form; Kotlin prüft Revision, Referenzen, Grenzen und widersprüchliche Änderungen vor dem Commit.
- Neue Entitäten brauchen eine explizite Create-Operation. Unklare Referenzen ändern den Zustand nicht.
- State-Auflösung läuft nach vollständigem Primary-Stream. Der nächste Warmup/Prefill wartet im ersten Experiment auf ihren Abschluss; gemessen werden Dauer, Patch-Ergebnis und Snapshot-Größe, ohne Spieltext zu loggen.
- Der Primary-Prompt enthält stets Instruction, Szenario, kanonische Historie, deterministischen State-Snapshot und neue Aktion. Warmup ist exakt der Prefix desselben Turns.
- IndexedDB bleibt Eigentümer des States. Der Server bleibt zustandslos.

## Umfang der ersten Lieferung

Entity-Kartei, Ort, Besitz, Status, kurze offene Fakten und begrenzte Entitätsanlage. Keine Kampfregeln, Zeituhren, Memory-Retrieval oder automatische Reparatur sichtbarer Prosa.

## Laufzeit

`server.sh` startet Primary- und Secondary-`llama-server` mit getrennten Modellpfaden, Ports und Thread-Budgets. Der Sekundärport wird als Server-Option weitergegeben. Beide Kinder werden bei Ende des Launchers beendet.
