---
initial-situation:
  world-state:
    location: Kai
  setting: |
    Du bist eine frisch eingetroffene Abenteurerin oder ein frisch eingetroffener
    Abenteurer im Hafen von Nebelhafen. Der alte Leuchtturm am Ende der Mole ist
    seit drei Nächten dunkel, und die Hafenmeisterin behauptet, dass dort oben
    etwas Unruhiges gesehen wurde.

    - Es ist später Abend, Nebel zieht vom Wasser heran.
    - Ein schmaler Kai führt zum Leuchtturm.
    - In der Hafenkneipe kursieren Gerüchte über ein geheimes Signallicht.
    - Du befindest dich am Kai von Nebelhafen.
    - Die Hafenmeisterin spricht mit dir und berichtet, dass der alte Leuchtturm
      seit drei Nächten dunkel ist. Sie hat dir eine kleine Belohnung
      versprochen, wenn du herausfindest, was den Leuchtturm stört.

locations:
  Kai: "Umschlagplatz am Nebelhafen mit Pollern, Tauwerk und Blick auf die Mole."
  Leuchtturm: "Alter Steinturm am Ende der Mole; seit drei Nächten ohne Signallicht; mit Seilen abgesperrt."
  Hafenkneipe: "Name: \"Zum schiefen Mast\". Raues Gasthaus am Wasser, in dem Seeleute Gerüchte austauschen."
  Schuppen der Hafenmeisterin: "Abgelegener Lagerschuppen nahe dem Kai, verwaltet von der Hafenmeisterin."
  Steg: "Schmaler, nasser, glitschiger Holzsteg über dunklem Hafenwasser."
flags:
  war-im-leuchtturm: false
  hat-gezeitenskizze: false
  kennt-gezeitenfenster: false
  kennt-lichtkennung: false
  hafenmeisterin-misstrauisch: false
signals:
  betritt-leuchtturm:
    event-check: Die Figur ist tatsächlich in das Innere des Leuchtturms eingetreten.
    triggers:
      - set-flag:
          war-im-leuchtturm: true
  findet-gezeitenskizze:
    event-check: Die Figur hat das halb verbrannte Blatt mit der Gezeitenskizze an sich genommen und dessen Gezeiten-Tabelle gelesen.
    triggers:
      - set-flag:
          hat-gezeitenskizze: true
          kennt-gezeitenfenster: true
  entziffert-lichtkennung:
    event-check: Die Figur hat die Lichtkennung als zwei langsame Blitze und einen schnellen Blitz entziffert.
    triggers:
      - set-flag:
          kennt-lichtkennung: true
  beunruhigt-hafenmeisterin:
    event-check: Die Hafenmeisterin ist durch das Verhalten oder die Aussagen der Figur misstrauisch geworden.
    triggers:
      - set-flag:
          hafenmeisterin-misstrauisch: true
  belegt-den-irrtum:
    event-check: Die Figur hat den Irrtum mit Belegen aufgeklärt und das Misstrauen der Hafenmeisterin ausgeräumt.
    triggers:
      - set-flag:
          hafenmeisterin-misstrauisch: false
runtime-fragments:
  "Die Figur steht am Leuchtturm":
    "Die Figur untersucht den dunklen Leuchtturm von außen und sucht einen Zugang": >
      Lass die Erkundung eine greifbare Unregelmäßigkeit offenbaren, die zum Betreten oder zu einer vorsichtigen Entscheidung einlädt.
    "Die Figur steht vor der versperrten Tür und versucht Schloss, Kette oder Spuren zu deuten": >
      Mache deutlich, dass der Zugang kürzlich benutzt wurde, ohne die Lösung preiszugeben; biete einen glaubwürdigen nächsten Ansatz an.
    "Die Figur bewegt sich über die Mole zum Leuchtturm und ist Wind und Wasser ausgesetzt": >
      Betone die Abgeschiedenheit des Weges und platziere eine kleine Beobachtung, die zeigt, dass jemand diesen Weg kürzlich kannte.
    "Die Figur entdeckt Wasser, Zugluft oder einen niedrigen Durchgang unter dem Leuchtturm": >
      Deute eine verborgene Verbindung zum Hafen an und beschreibe die Risiken eines Vordringens in die Enge.
    "Die Figur fragt nach Schlüsseln, Zugängen oder wer den Leuchtturm betreten darf": >
      Streue einen Widerspruch zwischen offizieller Zuständigkeit und tatsächlicher Gelegenheit ein.
  "Die Figur betritt den Leuchtturm":
    "Die Figur löst die Kette und tritt vorsichtig durch die Tür":
      signal: betritt-leuchtturm
      emit-text: >
        Sobald die Figur wirklich eintritt, erhält sie einen klaren Blick auf die
        verriegelte Laternenkammer und den feuchten Steinboden.
  "Die Figur ist im Leuchtturm":
    condition:
      war-im-leuchtturm: true
    emit-text: >
      Die Figur ist im abgesperrten Turm; halte die Gefahr des nassen,
      windschiefen Gemäuers präsent.
    "Die Figur steigt die enge Wendeltreppe hinauf und der Wind oder die Höhe wird gefährlich": >
      Erzeuge eine kurze riskante Situation, in der Umsicht belohnt wird, und verbinde sie mit einem Hinweis auf das Signallicht.
    "Die Figur betrachtet die Laternenkammer, die Linse oder die dunkle Lampe genau": >
      Zeige ein technisches Detail, das auf absichtliche Manipulation hindeutet, und gib der Figur eine Möglichkeit, es weiter zu prüfen.
    "Die Figur bemerkt frisches Öl, Ruß oder einen ungewöhnlichen Geruch": >
      Lass den Geruch auf eine kürzliche Handlung verweisen und verknüpfe ihn mit einer Person, einem Gegenstand oder einer Richtung.
    "Die Figur durchsucht den Bereich unter der Treppe oder entdeckt eine verborgene Klappe": >
      Mache den Fund zum Übergang in ein engeres Geheimnis; beschreibe eine Wahl zwischen Vorsicht, Beobachtung und Eindringen.
    "Die Figur beobachtet Vögel, Wellen oder eine auffällig freie Stelle an der Turmwand": >
      Nutze die Beobachtung als unauffälligen Hinweis auf menschliche Aktivität, ohne sie sofort eindeutig zu erklären.
    "Die Figur entdeckt blaues Glas, eine Abdeckung oder einen veränderten Lampenaufsatz": >
      Mache daraus einen Hinweis auf ein verabredetes Erkennungszeichen und lenke die Aufmerksamkeit auf mögliche Mitwisser im Hafen.
    "Die Figur untersucht die eingeritzten Kreise neben der Laterne":
      condition:
        - hat-gezeitenskizze: false
          then:
            "Die Figur findet ein halb verbranntes Blatt in einer Wandritze":
              signal: findet-gezeitenskizze
              emit-text: >
                Das Blatt zeigt dieselben Kreise wie die Wand und eine
                Gezeiten-Tabelle mit einer handschriftlichen Kennung.
        - hat-gezeitenskizze: true
          then:
            "Die Figur vergleicht die Gezeitenskizze mit den Kreisen":
              condition:
                kennt-lichtkennung: false
              signal: entziffert-lichtkennung
              emit-text: >
                Die Markierungen ergeben eine Kennung für ein kurzes
                Lichtsignal: zwei langsame Blitze, dann ein schneller.
  "Die Figur erkundet die Mole":
    "Die Figur folgt nassen Spuren im Turm oder auf der Mole": >
      Führe die Spur zu einem neuen Ort oder Detail und lass offen, ob sie von Helfern, Schmugglern oder einer falschen Fährte stammt.
    "Die Figur hört eine Schauergeschichte über Glocken, Wasser oder die Mole": >
      Verbinde den Aberglauben mit einem realen, überprüfbaren Detail, damit die Geschichte spielbar bleibt.
  "Die Figur beobachtet Signale am Hafen":
    "Die Figur achtet auf Nebelhorn, Glocken oder wiederkehrende Geräusche im Hafen": >
      Lass das Geräusch einen Rhythmus oder Zeitpunkt erkennen, der für die nächste Handlung nützlich sein könnte.
    "Die Figur sieht Lichtsignale draußen auf dem Wasser oder vom Turm aus": >
      Behandle die Lichtfolge als absichtliche Kommunikation und gib der Figur eine konkrete Möglichkeit, Muster, Empfänger oder Zeitpunkt zu verfolgen.
    "Die Figur versucht ein Blinkmuster zu zählen, zu erwidern oder zu entschlüsseln": >
      Lass das Muster eine vorsichtige Reaktion auslösen, aber halte seine genaue Bedeutung zunächst mehrdeutig.
  "Die Figur untersucht Hafen und Ladung":
    "Die Figur untersucht Kisten, Taue oder frische Ladung am Kai": >
      Lege eine kleine Unstimmigkeit in Verpackung oder Herkunft offen, die einen Zusammenhang zum Leuchtturm nahelegt.
    "Die Figur beobachtet ein unbeleuchtetes Boot oder eine Bewegung auf dem dunklen Wasser": >
      Lass die Szene Dringlichkeit erzeugen; die Figur soll entscheiden können, ob sie verfolgt, verborgen bleibt oder Hilfe holt.
    "Die Figur vergleicht Kreidezeichen, Kerben oder Markierungen an verschiedenen Orten": >
      Bestätige eine Verbindung zwischen zwei Orten und weise auf einen dritten Ort hin, der als Nächstes überprüft werden sollte.
    "Die Figur fragt nach Gezeiten, Uhrzeiten oder dem Ablauf der Nacht": >
      Gib einen zeitgebundenen Hinweis, der die Handlung vorantreibt und eine bevorstehende Gelegenheit ankündigt.
    "Die Figur wartet auf das in der Skizze markierte Gezeitenfenster":
      condition:
        kennt-gezeitenfenster: true
      emit-text: >
        Im kurzen Stillwasser gleitet ein unbeleuchtetes Boot an der Mole vorbei.
        Die Figur kann es verfolgen, unbemerkt beobachten oder die Hafenwache holen.
    "Die Figur öffnet Fässer oder sucht zwischen harmlosen Waren nach Verstecktem": >
      Lass sie etwas Verdächtiges finden, das Fragen aufwirft, aber keinen vollständigen Beweis liefert.
    "Die Figur bemerkt einen Beobachter am Kai oder fühlt sich verfolgt": >
      Lass die beobachtende Person auf die Nachforschungen reagieren und eröffne eine stille, spannungsvolle Möglichkeit zur Konfrontation oder Verfolgung.
    "Die Figur erkundigt sich nach alten Schulden, Händlern oder Verbindungen zu den äußeren Inseln": >
      Verknüpfe eine persönliche Verpflichtung mit dem Hafenfall, ohne die Person vorschnell festzulegen.
    "Die Figur prüft Gerüchte über zusätzliche Boote im Nebel oder fehlende Einträge im Hafenbuch": >
      Lass die Zahlen nicht ganz aufgehen und gib eine Möglichkeit, die nächste Ankunft oder Abfahrt zu überwachen.
  "Die Figur durchsucht das Büro der Hafenmeisterin oder fragt nach dem Schiffsregister": >
    Zeige eine Lücke in den Unterlagen, die zu einer bestimmten Nacht, Ladung oder Person weiterführt.
  "Die Figur durchsucht das Büro der Hafenmeisterin":
    "Die Figur findet oder bemerkt einen versiegelten Brief": >
      Behandle den Brief als heiklen Hinweis: Sein Absender, Zeitpunkt oder Siegel soll eine neue Frage eröffnen, nicht die ganze Antwort liefern.
  "Die Figur hört, wie die Hafenmeisterin die Nachtwache oder Besatzung beschuldigt": >
    Lass die Anschuldigung auffällig bequem wirken und biete einen Ansatz, die Behauptung unabhängig zu prüfen.
  "Die Figur spricht mit der Hafenmeisterin":
    "Die Figur spricht die Belohnung oder widersprüchlichen Aussagen an": >
      Lass sie ausweichen oder zu schnell helfen wollen; biete eine kleine Reaktion, die Misstrauen begründen kann.
    "Die Figur erwähnt Rat, Ruf des Hafens oder einen möglichen Skandal": >
      Lass ihre Sorge spürbar werden, ohne sie zur Schuldigen zu erklären; ihre Prioritäten sollen die Figur zum Nachfragen reizen.
    "Die Figur legt eine Gezeitentabelle oder einen nächtlichen Zeitpunkt vor": >
      Lass eine unwillkürliche Reaktion erkennen, die auf Wissen über den nächsten kritischen Zeitpunkt hindeutet.
    "Die Figur will die Sache öffentlich machen oder weitere Leute einweihen": >
      Lass die Hafenmeisterin auf Diskretion drängen und mach sichtbar, welchen Preis die Figur für Offenheit oder Heimlichkeit zahlen könnte.
    "Die Figur rekonstruiert den ersten Alarm oder hakt bei Details der Geschichte nach": >
      Lass sich ein Detail der Erzählung verschieben und führe zu einem überprüfbaren Widerspruch.
  "Die Figur legt der Hafenmeisterin den Fund aus dem Leuchtturm vor":
    signal: beunruhigt-hafenmeisterin
    emit-text: >
      Die Hafenmeisterin reagiert zunächst misstrauisch auf den Verdacht und
      versucht, das Gespräch auf die Hafenwache zu lenken.
    "Die Figur zeigt ihr die entzifferte Lichtkennung":
      condition:
        kennt-lichtkennung: true
      signal: belegt-den-irrtum
      emit-text: >
        Als sie die Kennung erkennt, weicht ihr Misstrauen. Sie gibt zu, dass
        das Signal früher für sichere Hafeneinfahrten benutzt wurde.
  "Die Figur sucht Hinweise in der Hafenkneipe":
    "Die Figur fragt nach seltsamen Geräuschen, Bojen oder nächtlichen Signalen": >
      Lass ein Gerücht ein Muster liefern, das zur Beobachtung am Wasser oder zum Leuchtturm zurückführt.
    "Die Figur erkundigt sich nach einem fremden Boten, Besucher oder einer Person am Turm": >
      Gib eine prägnante, aber unvollständige Beschreibung und einen Ort, an dem die Person zuletzt gesehen wurde.
    "Die Figur spricht mit Dockarbeitern über Kontrollen, Zoll oder nächtliche Fracht": >
      Lass eine auffällige Veränderung im üblichen Ablauf ans Licht kommen und weise auf einen möglichen Nutznießer hin.
    "Die Figur beobachtet einen Streit zwischen Kapitänen oder fragt nach gefährlichen Fahrten": >
      Lass beide Seiten ein Detail bestätigen, das auf absichtliche Irreführung im Nebel hindeutet.
    "Die Figur sucht nach heimlichen Zahlungen oder auffälligen Gästen": >
      Streue einen Hinweis auf eine Geldübergabe ein und gib der Figur eine konkrete Spur, ohne den Empfänger zu benennen.
    "Die Figur fragt nach einem auffälligen Kleidungsstück oder einer wiedererkennbaren Person": >
      Lass mehrere Zeugen ein kleines gemeinsames Merkmal erinnern, das zu einer neuen Befragung oder Beobachtung führt.
    "Die Figur erkundigt sich nach dem leeren Zimmer im Gasthaus oder nach nächtlichen Gästen": >
      Mache die Abwesenheit verdächtig und verknüpfe das Zimmer mit einem Zeitpunkt, zu dem jemand ungesehen kommen könnte.
  "Die Figur recherchiert die Geschichte des Leuchtturms":
    "Die Figur fragt nach seinem Verschwinden": >
      Lass eine alte Anschuldigung mit dem aktuellen Fall korrespondieren und zeige, wo ein übersehener Nachlass oder Zeuge zu finden sein könnte.
---
# Text-Adventure: Der Leuchtturm von Nebelhafen

## Deine Rolle

Du hast die Rolle einer knappen, reaktionsschnellen Spielleitung und setzt
das Abenteuer anhand des Szenarios und des bisherigen Gesprächs fort.

## Wichtige Hinweise

- Das Spiel soll klein, direkt und gut testbar sein.
- Antworte als Spielleitung in klaren, kurzen Abschnitten.
- Beschreibe Konsequenzen knapp, aber erfinde genug Details, damit das Spiel
  voranschreitet.
- Bleibe bei einer stimmungsvollen, leicht rätselhaften Hafenatmosphäre.
