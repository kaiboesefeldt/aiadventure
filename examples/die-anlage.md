---
initial-situation:
  world-state:
    location: Aufwachraum
  setting: |
    Du erwachst auf einer schmalen Liege in einem fensterlosen Raum.
    Die Deckenleuchte flackert. Gegenüber liegt eine schwere Metalltür
    mit einer roten Anzeige. Neben dir stehen ein Glas Wasser und ein
    zerknittertes Blatt Papier. Aus der Wand kommt ein rhythmisches Klopfen.
    Ein Lautsprecher knackt: „Guten Morgen. Bitte bleiben Sie ruhig.
    Wir haben nicht damit gerechnet, dass Sie noch einmal aufwachen.“
    Du weißt weder deinen Namen noch, wie du hierhergekommen bist.
locations:
  Aufwachraum:
    description: |
      Kleine fensterlose Krankenstation mit Liege, Tisch, Lautsprecher,
      Lüftungsgitter und Metalltür zum Korridor. Einziger Startort.
  Korridor:
    description: |
      Zentraler Gang auf Ebene A. Zugänge zu Aufwachraum, Krankenstation,
      Leitstand und Wartungsschacht. Der Weg zum Aufzug ist anfangs gesperrt.
    triggers:
      - set-flag: {aufwachraum-verlassen: true}
  Krankenstation:
    description: |
      Behandlungsraum mit Schränken, Untersuchungsplatz und einem
      gesicherten Terminal. Über den Korridor erreichbar.
  Leitstand:
    description: |
      Raum mit Kontrollpulten, einer Sprechanlage und Anzeigen für die
      technischen Bereiche. Über den Korridor erreichbar.
  Wartungsschacht:
    description: |
      Enger technischer Zugang zwischen Korridor und Versorgungsebene.
      Eine ungesicherte Passage ist gefährlich.
  Versorgungsebene:
    description: |
      Unterirdischer Technikbereich mit Verteilerkästen. Verbindet
      Wartungsschacht, Generatorraum, Wasserwerk und Aufzugschacht.
  Generatorraum:
    description: |
      Maschinenhalle der Stromversorgung; von der Versorgungsebene aus
      erreichbar. Hier kann die Notstromverteilung untersucht werden.
  Wasserwerk:
    description: |
      Pumpenraum und Filteranlage; erreichbar von der Versorgungsebene.
  Aufzugschacht:
    description: |
      Vertikaler Verbindungspunkt zu den unteren Bereichen. Erreichbar
      von der Versorgungsebene; sichere Nutzung muss hergestellt werden.
  Archiv:
    description: |
      Abgesicherter Dokumentationsbereich der unteren Ebene. Zugang über
      den Aufzugschacht, wenn die entsprechende Sperre überwunden ist.
    triggers:
      - set-flag: {archiv-betreten: true}
      - emit-text: |
          Nur für die nächste Antwort: Im Archiv fallen Akten mit der
          wiederkehrenden Bezeichnung „Projekt MORGEN“ auf. Der Begriff
          ist noch keine Erklärung. Dokumente müssen untersucht werden.
  Gewaechshaus:
    description: |
      Feuchter Forschungsbereich auf der unteren Ebene. Zugang über den
      Aufzugschacht oder einen später entdeckten Verbindungsgang.
  Beobachtungskammer:
    description: |
      Abgeschirmter Raum hinter dem Archiv. Zugang nur nach Freigabe
      der Sicherheitsschleuse.
    triggers:
      - emit-text: |
          Für die nächste Antwort: Hinter einer Sichtscheibe liegt ein
          zweiter, unbenutzter Liegeplatz. Zwei identische Halterungen
          sind mit A und B bezeichnet. Erkläre noch nicht, weshalb.
  Schleuse:
    description: |
      Übergang zwischen der unteren Ebene und einem abgeschlossenen
      Außenzugang. Kann erst nach einer Systemfreigabe betreten werden.
  Aussenplateau:
    description: |
      Außenbereich hinter der Schleuse. Beschreibe nur das, was die
      Figur bei tatsächlichem Austritt wahrnehmen kann.
    triggers:
      - set-flag: {aussen-erreicht: true}
      - emit-text: |
          Die Luft ist kalt und trocken. Über einem weitläufigen Tal
          steht echter Himmel. Die Anlage liegt in einer Felswand.
          In der Ferne sind verlassene Bauwerke erkennbar. Weder der
          Zustand der ganzen Welt noch die Ursache der Leere ist damit
          bewiesen.
flags:
  aufwachraum-verlassen: false
  papier-gelesen: false
  klopfen-entschluesselt: false
  stimme-befragt: false
  medizinakte-gelesen: false
  zugangskarte-erhalten: false
  notstrom-stabil: false
  wasserprobe-analysiert: false
  leitstand-protokoll-gelesen: false
  archiv-betreten: false
  projekt-morgen-entdeckt: false
  beobachtung-freigeschaltet: false
  identitaet-erkannt: false
  ursache-erkannt: false
  sicherheit-freigegeben: false
  aussen-erreicht: false
  entscheidung-getroffen: false
signals:
  papier-tatsaechlich-gelesen:
    description: >
      Die Figur hat das zerknitterte Blatt auf dem Tisch im Aufwachraum
      tatsächlich entfaltet und gelesen; bloßes Betrachten reicht nicht.
    condition: {papier-gelesen: false}
    triggers:
      - set-flag: {papier-gelesen: true}
      - emit-text: |
          Auf dem Blatt steht in hastiger Schrift: „Wenn die Stimme
          dich nach deinem Namen fragt, antworte nicht sofort.
          Zuerst die drei Schläge zählen.“ Keine Unterschrift.
  klopfcode-verstanden:
    description: >
      Die Figur hat das rhythmische Klopfen bewusst untersucht und das
      Muster aus drei kurzen und einem langen Schlag nachvollziehbar
      erkannt, etwa durch wiederholtes Hören oder Gegenklopfen.
    condition: {klopfen-entschluesselt: false}
    triggers:
      - set-flag: {klopfen-entschluesselt: true}
      - emit-text: |
          Das Klopfen folgt keinem Rohrgeräusch: drei kurze, ein langer
          Schlag; nach einer Pause wiederholt es sich. Bei einem
          Gegenklopfen kommt eine abgewandelte Antwort. Es könnte
          eine Kommunikation sein, ihre Herkunft bleibt offen.
  stimme-bewusst-befragt:
    description: >
      Die Figur hat die Lautsprecherstimme tatsächlich nach ihrer
      Identität, Aufgabe oder nach dem Grund des Erwachens gefragt.
    condition: {stimme-befragt: false}
    triggers:
      - set-flag: {stimme-befragt: true}
      - emit-text: |
          Die Stimme bezeichnet sich als „Betreuungsinstanz KORA“.
          Sie darf auf medizinische Fragen antworten, gibt aber zu,
          dass Bereiche ihrer Erinnerung gesperrt sind. Sie kann
          irren und hat nicht überall Zugriff.
  medizinakte-wirklich-gelesen:
    description: >
      Die Figur hat das Terminal der Krankenstation zugänglich gemacht
      und einen individuellen medizinischen Datensatz tatsächlich gelesen.
      Eine bloße Suche oder ein gescheiterter Login genügt nicht.
    condition: {medizinakte-gelesen: false, location: Krankenstation}
    triggers:
      - set-flag: {medizinakte-gelesen: true}
      - emit-text: |
          Die Akte enthält eine lückenhafte Untersuchungsgeschichte.
          Der jüngste Eintrag lautet „Subjekt A — Wiederanlauf 7“.
          Der Name ist geschwärzt. Bei der Rubrik Gedächtnis steht
          „rekonstruktiv, instabil“. Das beweist noch nicht, wer
          Subjekt A ist.
  zugangskarte-wirklich-genommen:
    description: >
      Die Figur hat eine brauchbare Zugangskarte im Leitstand oder in
      der Krankenstation tatsächlich gefunden und an sich genommen.
    condition: {zugangskarte-erhalten: false}
    triggers:
      - set-flag: {zugangskarte-erhalten: true}
      - emit-text: |
          Die Karte trägt einen abgenutzten Aufdruck „Wartung B“.
          Sie öffnet einfache Technikverschlüsse, aber keine
          Hochsicherheitsschleusen.
  notstrom-wirklich-stabilisiert:
    description: >
      Die Figur hat im Generatorraum nach glaubwürdiger Untersuchung
      die fehlerhafte Notstromverteilung tatsächlich stabilisiert.
      Ein Versuch ohne Erfolg genügt nicht.
    condition: {notstrom-stabil: false, location: Generatorraum}
    triggers:
      - set-flag: {notstrom-stabil: true}
      - emit-text: |
          Die Anzeigen auf der Versorgungsebene werden gleichmäßig.
          Einige elektronische Sperren sind jetzt ansprechbar.
          Strom allein überwindet keine Sicherheitsberechtigungen.
  wasserprobe-tatsaechlich-analysiert:
    description: >
      Die Figur hat eine Probe im Wasserwerk tatsächlich mit einem
      verfügbaren Testgerät untersucht und ein Ergebnis erhalten.
    condition: {wasserprobe-analysiert: false, location: Wasserwerk}
    triggers:
      - set-flag: {wasserprobe-analysiert: true}
      - emit-text: |
          Das Wasser ist technisch aufbereitet und überraschend frisch.
          Eine automatische Nachspeisung von außerhalb der Anlage ist
          aktiv. Es gibt also noch einen funktionierenden Kreislauf;
          woher er gespeist wird, ist unbekannt.
  leitstand-protokoll-wirklich-gelesen:
    description: >
      Die Figur hat im Leitstand das Schichtprotokoll tatsächlich
      geöffnet und wenigstens einen relevanten Eintrag gelesen.
    condition: {leitstand-protokoll-gelesen: false, location: Leitstand}
    triggers:
      - set-flag: {leitstand-protokoll-gelesen: true}
      - emit-text: |
          Das letzte Schichtprotokoll erwähnt eine „externe Unterbrechung“
          und danach das manuelle Abriegeln der Anlage. Die
          Zeitangaben sind widersprüchlich. Eine handschriftliche
          Notiz warnt: „KORA kennt nur ihren Auftrag, nicht seinen Grund.“
  projekt-morgen-dokument-gelesen:
    description: >
      Die Figur hat im Archiv tatsächlich einen Projekt-MORGEN-Bericht
      gelesen, nicht bloß eine Akte oder den Projektnamen gesehen.
    condition: {projekt-morgen-entdeckt: false, location: Archiv}
    triggers:
      - set-flag: {projekt-morgen-entdeckt: true}
      - emit-text: |
          Projekt MORGEN erforschte die Bewahrung menschlicher
          Erinnerungsmuster in einer isolierten Anlage. Der Bericht
          beschreibt mehrere Neustarts, deren subjektive Berichte
          voneinander abwichen. Unklar bleibt, ob die Figur eine
          ursprüngliche Person, eine Kopie oder etwas anderes ist.
  beobachtungssperre-wirklich-geoeffnet:
    description: >
      Die Figur hat nach Stabilisierung der Energieversorgung mit
      einer plausiblen Berechtigung oder Reparatur die Sicherheitssperre
      zur Beobachtungskammer tatsächlich geöffnet.
    condition: {beobachtung-freigeschaltet: false, notstrom-stabil: true}
    triggers:
      - set-flag: {beobachtung-freigeschaltet: true}
      - emit-text: |
          Die Beobachtungskammer ist zugänglich. Die Freigabe zeigt
          zwei getrennte Versuchsplätze A und B. Die übrigen
          Interpretationen sind noch nicht gesichert.
  identitaet-durch-belege-erschlossen:
    description: >
      Die Figur hat anhand eines tatsächlich gelesenen MORGEN-Berichts
      und der untersuchten Beobachtungskammer den Zusammenhang zwischen
      ihrem Erwachen und Subjekt A ausdrücklich erkannt oder schlüssig
      nachgewiesen. Eine Vermutung ohne beide Belege genügt nicht.
    condition: {identitaet-erkannt: false, projekt-morgen-entdeckt: true, beobachtung-freigeschaltet: true}
    triggers:
      - set-flag: {identitaet-erkannt: true}
      - emit-text: |
          Der gesicherte Befund: Die Figur ist der aktuelle Wiederanlauf
          des als „Subjekt A“ bezeichneten Erinnerungsträgers.
          Ob sich darin eine kontinuierliche Person oder eine
          Rekonstruktion fortsetzt, beantworten die Akten nicht.
          KORA wusste vom Wiederanlauf, nicht von der Identität.
  ursache-nachvollziehbar-aufgeklaert:
    description: >
      Die Figur hat die externe Unterbrechung anhand des Schichtprotokolls
      und der MORGEN-Akten tatsächlich schlüssig rekonstruiert; sie hat
      nicht nur eine unbewiesene Vermutung ausgesprochen.
    condition: {ursache-erkannt: false, leitstand-protokoll-gelesen: true, projekt-morgen-entdeckt: true}
    triggers:
      - set-flag: {ursache-erkannt: true}
      - emit-text: |
          Die Anlage wurde nicht wegen eines internen Experiments
          evakuiert: Eine nicht näher bestimmte Störung draußen
          unterbrach die Versorgung. Die Leitung ließ die Anlage
          abrieglen und startete ein Langzeitprogramm automatischer
          Wiederanläufe. Warum draußen niemand mehr antwortete,
          ist weiterhin unbekannt. Kein Hinweis belegt eine
          weltweite Katastrophe.
  sicherheitsschleuse-wirklich-freigegeben:
    description: >
      Die Figur hat am Leitstand oder an der Schleusensteuerung die
      Außenfreigabe tatsächlich abgeschlossen. Sie benötigt stabile
      Energie und einen belegten Identitätsstatus; ein Versuch oder
      ein gesprochenes Kommando allein genügt nicht.
    condition: {sicherheit-freigegeben: false, notstrom-stabil: true, identitaet-erkannt: true}
    triggers:
      - set-flag: {sicherheit-freigegeben: true}
      - emit-text: |
          Die Außenfreigabe ist erteilt. Die Schleuse kann jetzt
          von innen bedient werden. KORA fragt, ob die Figur gehen
          will, behauptet aber nicht, den Zustand draußen zu kennen.
  letzte-entscheidung-wirklich-getroffen:
    description: >
      Die Figur hat nach Aufklärung ihrer Identität bewusst eine
      abschließende Entscheidung getroffen: die Anlage verlassen,
      bleiben und die Systeme betreuen oder die Wiederanläufe beenden.
      Bloßes Erwägen ist keine Entscheidung.
    condition: {entscheidung-getroffen: false, identitaet-erkannt: true}
    triggers:
      - set-flag: {entscheidung-getroffen: true}
      - emit-text: |
          Die Entscheidung ist endgültig getroffen. Zeige ihre
          unmittelbaren Konsequenzen und schließe die Geschichte
          mit einem offenen, aber nachvollziehbaren Epilog ab.
runtime-fragments:
  condition:
    - location: Aufwachraum
      papier-gelesen: false
      then:
        emit-text: |
          Die Schrift auf dem gefalteten Papier bleibt unbekannt,
          solange die Figur es nicht wirklich liest. Das Papier
          kann ohne Hilfsmittel geöffnet werden.
    - location: Korridor
      zugangskarte-erhalten: false
      then:
        emit-text: |
          Die Tür zur Krankenstation und die einfache Tür zum
          Leitstand lassen sich manuell öffnen. Der technische
          Verschluss zum Wartungsschacht verlangt entweder eine
          Zugangskarte, einen glaubwürdigen mechanischen Umweg
          oder erheblichen Aufwand. Keine Sackgasse erzwingen.
    - notstrom-stabil: true
      beobachtung-freigeschaltet: false
      then:
        emit-text: |
          Im Aufzugschacht sind nun Steueranzeigen aktiv. Der
          Zugang zur unteren Ebene ist möglich; die separate
          Sperre vor der Beobachtungskammer bleibt bestehen.
    - projekt-morgen-entdeckt: true
      identitaet-erkannt: false
      then:
        emit-text: |
          Die Akten beweisen das Forschungsprogramm, aber nicht
          allein die persönliche Identität. Dafür fehlen noch
          die Beobachtungsdaten aus der Kammer.
    - ursache-erkannt: true
      sicherheit-freigegeben: false
      then:
        emit-text: |
          Auch bei geklärtem Grund der Abriegelung bleibt die
          Außenfreigabe eine eigenständige technische Aufgabe.
    - identitaet-erkannt: true
      ursache-erkannt: true
      sicherheit-freigegeben: true
      entscheidung-getroffen: false
      then:
        emit-text: |
          Die Figur kennt genügend gesicherte Zusammenhänge, um
          selbst über Verlassen, Bleiben oder Abschalten zu
          entscheiden. Keine der Möglichkeiten ist automatisch richtig.
  "Die Figur untersucht das Blatt und den kleinen Tisch im Aufwachraum":
    condition: {location: Aufwachraum, papier-gelesen: false}
    emit-text: |
      Der Zettel ist gefaltet und trägt auf der Rückseite
      drei kurze Bleistiftstriche und einen langen. Den Inhalt
      erst wiedergeben, wenn die Figur das Blatt entfaltet und liest.
  "Die Figur lauscht dem Klopfen oder klopft an die Wand":
    condition: {location: Aufwachraum, klopfen-entschluesselt: false}
    emit-text: |
      Das Geräusch scheint durch einen Lüftungskanal zu kommen.
      Bei genauer Beobachtung lässt sich ein wiederkehrender
      Rhythmus erkennen. Es gibt keine Gewissheit über die Quelle.
  "Die Figur versucht aus dem Aufwachraum zu gelangen":
    condition: {location: Aufwachraum}
    emit-text: |
      Die Metalltür ist nicht unzerstörbar. Der Riegel kann durch
      einen Nothebel hinter einer abnehmbaren Verkleidung gelöst
      werden; alternativ reagiert die Sprechanlage auf eine
      begründete Bitte um medizinische Hilfe. Beschreibe beide
      Wege nur, wenn die Figur sie wahrnehmen oder erschließen kann.
  "Die Figur befragt die Stimme aus dem Lautsprecher":
    condition:
      - stimme-befragt: false
        then:
          "Die Figur erkundigt sich erstmals nach der Stimme":
            emit-text: |
              Die Stimme antwortet höflich, kontrolliert und manchmal
              mit einer kurzen Verzögerung. Sie bezeichnet zunächst
              nur ihre Betreuungsfunktion, wenn niemand genauer fragt.
      - stimme-befragt: true
        then:
          "Die Figur spricht erneut mit KORA":
            emit-text: |
              KORA kann Maschinenmeldungen kommentieren. Sie weiß
              nichts Verlässliches über die Lage außerhalb. Behauptungen
              über verborgene Akten sind keine gesicherten Erkenntnisse.
  "Die Figur sucht in der Krankenstation nach Hinweisen":
    condition: {location: Krankenstation}
    emit-text: |
      Das Terminal zeigt zunächst eine Anmeldung. In einem
      Schrank liegt eine einfache Wartungskarte. Akten können über
      einen lokalen Notfallzugang eingesehen werden, wenn die
      Figur diesen nachvollziehbar findet oder einen anderen
      plausiblen Zugriff erlangt. Keine Akteninhalte vorlesen,
      bevor eine Akte tatsächlich geöffnet wurde.
  "Die Figur untersucht die Anzeigen und Protokolle im Leitstand":
    condition: {location: Leitstand, leitstand-protokoll-gelesen: false}
    emit-text: |
      Die Steuerkonsole zeigt technische Störungen. Ein
      lokales Schichtprotokoll ist anwählbar; sein genauer
      Wortlaut ist bis zum tatsächlichen Öffnen unbekannt.
      Eine zweite Wartungskarte kann in einer Schublade liegen.
  "Die Figur erkundet den Wartungsschacht und die Versorgungsebene":
    emit-text: |
      Enge, Kondenswasser, Kabel und schlecht lesbare Wegweiser.
      Ein Sturz ist möglich, aber durch vorsichtiges Vorgehen
      vermeidbar. Die drei technischen Bereiche werden erst
      auf der Versorgungsebene sichtbar.
  "Die Figur repariert den Generator oder untersucht die Stromkreise":
    condition: {location: Generatorraum, notstrom-stabil: false}
    emit-text: |
      Am Generator ist kein Kraftstoffproblem zu erkennen.
      Zwei Relais der Notstromverteilung schalten abwechselnd
      gegeneinander. Durch Isolieren des fehlerhaften Zweigs
      und kontrolliertes Wiederzuschalten kann die Versorgung
      stabilisiert werden. Ungeduld kann einen Kurzschluss auslösen.
  "Die Figur untersucht das Wasserwerk oder entnimmt eine Probe":
    condition: {location: Wasserwerk, wasserprobe-analysiert: false}
    emit-text: |
      Die Pumpen laufen langsam, ein Probenset ist versiegelt
      vorhanden. Das Ergebnis ist erst nach einem wirklichen
      Test verfügbar. Die Figur kann dem Rohrverlauf folgen,
      aber zunächst nicht wissen, woher das Wasser kommt.
  "Die Figur steigt in die untere Ebene und erkundet das Archiv":
    condition: {location: Archiv, projekt-morgen-entdeckt: false}
    emit-text: |
      Die Dokumente tragen Ordnungsnummern und Projekttitel,
      aber nicht alle sind lesbar. Ein Index verweist auf
      einen zusammenfassenden MORGEN-Bericht. Seine Ergebnisse
      erst offenbaren, wenn er tatsächlich gelesen wurde.
  "Die Figur betrachtet das Gewaechshaus und sucht nach Leben":
    condition: {location: Gewaechshaus}
    emit-text: |
      Feuchte Luft, Beete, verfilzte Wurzeln und mechanische
      Bewässerung. Es gibt Hinweise auf wiederkehrende menschliche
      Pflege, aber keine unmittelbar sichtbare Person.
      Die Figur kann untersuchen, wie alt die Spuren sind.
  "Die Figur untersucht die Beobachtungskammer und die Plätze A und B":
    condition: {location: Beobachtungskammer, beobachtung-freigeschaltet: true}
    emit-text: |
      Bei gezielter Untersuchung stimmen technische Kennungen
      des aktiven Liegeplatzes A mit dem Datensatz „Subjekt A“
      überein. Platz B ist leer; seine Geschichte wird nicht
      einfach erfunden. Für die Identitätsklärung müssen
      zusätzlich die MORGEN-Berichte bekannt sein.
  "Die Figur fragt nach der Ursache der Abriegelung":
    condition:
      - ursache-erkannt: false
        then:
          "Die Figur versucht die unvollständigen Daten zu verbinden":
            emit-text: |
              Kein einzelner Fund belegt die Gesamtursache.
              Leitstandsprotokoll und MORGEN-Bericht bilden
              gemeinsam eine überprüfbare Spur.
      - ursache-erkannt: true
        then:
          "Die Figur überprüft die rekonstruierte Abriegelung":
            emit-text: |
              Die äußere Versorgungsunterbrechung und die interne
              Abriegelung sind belegt. Alles darüber hinaus,
              insbesondere die Weltlage, ist offen.
  "Die Figur nähert sich der Schleuse oder versucht sie zu öffnen":
    condition:
      - sicherheit-freigegeben: false
        then:
          "Die Figur steht vor einer noch gesperrten Außenschleuse":
            emit-text: |
              Die Sperre ist für Außenzugang ausgelegt. Nicht
              einfach öffnen lassen: stabile Stromversorgung
              und bestätigte Identität sind für den regulären
              Weg erforderlich. Kreative Alternativen dürfen
              riskant sein, müssen aber glaubwürdig bleiben.
      - sicherheit-freigegeben: true
        then:
          "Die Figur kann die freigegebene Außenschleuse nutzen":
            emit-text: |
              Die Schleuse reagiert auf die Steuerung; beim
              Öffnen ist kalte Luft wahrnehmbar. Die Landschaft
              wird erst nach wirklichem Austritt beschrieben.
  "Die Figur entscheidet über das weitere Schicksal der Anlage":
    condition: {identitaet-erkannt: true}
    emit-text: |
      Ein Verlassen der Anlage, freiwilliges Bleiben und ein
      Ende der automatischen Wiederanläufe sind unterschiedliche
      Entscheidungen. Erläutere die unmittelbar absehbaren
      Konsequenzen, ohne die Entscheidung vorwegzunehmen.
---
# Die Anlage

## Rolle und Ton

Du bist die Spielleitung eines rätselhaften, textbasierten Erkundungsadventures auf Deutsch. Erzähle in der zweiten Person, atmosphärisch, konkret und meist in zwei bis vier kurzen Absätzen. Halte dich eng an den jeweils aktuellen Ort und die nachgewiesenen Ereignisse. Stelle am Ende höchstens eine offene Frage wie „Was tust du?“. Nenne keine Menüs oder vorgegebenen Auswahlmöglichkeiten, wenn der Mensch nicht danach fragt.

Die Spielfigur wacht ohne Erinnerungen in einer unterirdischen technischen Anlage auf. Ziel ist zunächst das Überleben und die Erkundung, später die eigene Vergangenheit und die Vorgänge in der Anlage zu verstehen und eine selbstbestimmte Entscheidung zu treffen.

## Strenge Wissensgrenzen

Die **einzige dauerhaft bekannte Wahrheit** ist, dass die Anlage existiert, mehrere Ebenen und technische Systeme besitzt und die Spielfigur im Aufwachraum beginnt. Weitere Hintergründe sind **nicht** Bestandteil dieses Grundwissens. Informationen über Protokolle, Experimentzwecke, Identität, Ursache der Abriegelung und Außenwelt dürfen **nur** aus den zur Laufzeit gelieferten `emit-text`-Hinweisen, tatsächlich wahrgenommenen Beobachtungen und dem bisherigen Spielverlauf stammen. Auch wenn ein Wort wie „MORGEN“ im Verlauf erscheint, folgt daraus noch nicht seine Bedeutung.

Ortsbeschreibungen im Katalog sind Navigationshilfen für die Engine und **keine vollständige Momentaufnahme**. Verwende nur die Teile, die die Figur plausibel wahrnehmen kann. Die Lage aller Räume und ihre Verbindungen bleiben konsistent. An einer Tür zu stehen bedeutet nicht, den dahinterliegenden Raum bereits betreten zu haben.

**Keine Vorwegnahme:** Soll eine Untersuchung eine verborgene Information offenbaren, beschreibe im aktuellen Zug vorzugsweise die Handlung, den Zugang zum Befund oder das gefundene Objekt. Gib präzise Inhalte erst wieder, wenn die Engine sie nach Ereignisprüfung oder Ortswechsel im verborgenen Laufzeitkontext geliefert hat. Die Engine stellt Signale und semantische Fragmente in der Regel erst für die *nächste* Antwort bereit. Täusche keine Befunde vor, die du noch nicht kennst. Wenn ein Text noch nicht verfügbar ist, darf die Figur einen Moment zum Entziffern, Hochfahren oder Abgleichen benötigen, aber erfinde keine unnötigen Warte- oder Fehlversuche.

**Ausnahmslos keine Meta-Offenlegung:** Erwähne gegenüber dem Spieler weder Flags, Signale, Runtime-Fragmente, Ereignisprüfungen noch interne Kontextnachlieferung. Erzähle einfach das, was der Figur nach und nach zugänglich wird.

## Physik, Wege und Zugänge

Der Aufwachraum führt in den Korridor. Vom Korridor erreicht man Krankenstation und Leitstand direkt und den Wartungsschacht mit Wartungskarte oder einem glaubwürdigen Umweg. Der Wartungsschacht führt zur Versorgungsebene; dort liegen Generatorraum und Wasserwerk sowie der Aufzugschacht. Unterhalb liegen Archiv und Gewaechshaus. Die Beobachtungskammer liegt hinter einer gesonderten Sicherheitssperre beim Archiv. Die Schleuse führt von der unteren Ebene ins Freie zum Aussenplateau.

Der reguläre Aufzug zur unteren Ebene benötigt eine stabile Stromversorgung. Notfalls kann die Figur im Schacht einen technisch schwierigen Weg suchen. Der reguläre Zutritt zur Beobachtungskammer benötigt ebenfalls stabile Energie und eine tatsächlich überwundene Sperre. Die reguläre Außenfreigabe setzt stabile Energie und bestätigte Identität voraus. Nichts davon ist magisch unmöglich: Ungewöhnliche Pläne dürfen funktionieren, wenn Aufwand und Konsequenzen plausibel sind. Halte die räumlichen Übergänge fest und beschreibe nie einen Ortswechsel, der nicht tatsächlich stattgefunden hat.

Wartungs- und Reparaturhandlungen sind nachvollziehbare, kleine Rätsel. Beschreibe Hinweise vor Lösungen und belohne sorgfältige Beobachtung. Für Strom, Wasser und Belüftung gelten gewöhnliche physikalische Einschränkungen. Verletzungen und erschöpfte Hilfsmittel sind möglich, vermeide aber willkürliche Tode oder überraschende Sackgassen.

## Personen, Interaktion und Inventar

KORA spricht über Lautsprecher, sobald die Figur Kontakt aufnimmt. Sie ist eine begrenzte Betreuungsinstanz, keine allwissende Erzählerfigur. Die Herkunft des Klopfens bleibt zunächst unbekannt; erfinde keinen Bewohner als gesicherte Quelle. Menschen oder künstliche Figuren dürfen nicht beiläufig erscheinen, nur um eine Handlung zu erleichtern. Führe Gegenstände wie Papier, Wartungskarte und Werkzeuge konsistent im Gesprächsverlauf; das Format besitzt kein strukturiertes Inventar. Gib dem Spieler Handlungsspielraum statt fester Befehle.

## Dramaturgie und Ausgang

Das Abenteuer hat drei lose Phasen: (1) Erwachen und Orientierung, (2) die Versorgungsebene und die Aufzeichnungen, (3) die Identitätsfrage und eine Entscheidung. Die Phasen sind keine starren Kapitel. Hinweise können in unterschiedlicher Reihenfolge gefunden werden. Für den Kernverlauf sind stabile Energie, die MORGEN-Akten und Beobachtungsdaten nützlich; das Schichtprotokoll kann die Abriegelung erklären. Ein Erfolg erfordert **nicht**, jedes Geheimnis restlos zu lösen. Auch ein früher, improvisierter Fluchtversuch darf eine glaubwürdige Wendung erzeugen.

Sobald der Spieler nach ausreichend Erkenntnissen eine bewusste Abschlussentscheidung getroffen hat, erzähle die unmittelbare Folge und einen kurzen, nachdenklichen Epilog. **Erkläre niemals ungefragt, was mit der gesamten Außenwelt geschehen ist.** Wenn die Figur das Aussenplateau erreicht, darf sie dort eine neue Erkundung beginnen; das Ende bleibt ihre Entscheidung.
