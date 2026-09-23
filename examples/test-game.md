---
initial-situation:
  world-state:
    location: Kai
  setting: >
    Du befindest dich am Kai von Nebelhafen. Die Hafenmeisterin spricht mit dir
    und berichtet, dass der alte Leuchtturm seit drei Nächten dunkel ist.
locations:
  Kai: "Umschlagplatz am Nebelhafen mit Pollern, Tauwerk und Blick auf die Mole."
  Leuchtturm: "Alter Steinturm am Ende der Mole; seit drei Nächten ohne Signallicht."
  Hafenkneipe: "Raues Gasthaus am Wasser, in dem Seeleute Gerüchte austauschen."
  Schuppen der Hafenmeisterin: "Abgelegener Lagerschuppen nahe dem Kai, verwaltet von der Hafenmeisterin."
  Steg: "Schmaler, nasser Holzsteg über dunklem Hafenwasser."
runtime-fragments:
  "Die Figur untersucht den dunklen Leuchtturm von außen und sucht einen Zugang":
    fragment: "Lass die Erkundung eine greifbare Unregelmäßigkeit offenbaren, die zum Betreten oder zu einer vorsichtigen Entscheidung einlädt."
  "Die Figur steht vor der versperrten Tür und versucht Schloss, Kette oder Spuren zu deuten":
    fragment: "Mache deutlich, dass der Zugang kürzlich benutzt wurde, ohne die Lösung preiszugeben; biete einen glaubwürdigen nächsten Ansatz an."
  "Die Figur folgt nassen Spuren im Turm oder auf der Mole":
    fragment: "Führe die Spur zu einem neuen Ort oder Detail und lass offen, ob sie von Helfern, Schmugglern oder einer falschen Fährte stammt."
  "Die Figur steigt die enge Wendeltreppe hinauf und der Wind oder die Höhe wird gefährlich":
    fragment: "Erzeuge eine kurze riskante Situation, in der Umsicht belohnt wird, und verbinde sie mit einem Hinweis auf das Signallicht."
  "Die Figur betrachtet die Laternenkammer, die Linse oder die dunkle Lampe genau":
    fragment: "Zeige ein technisches Detail, das auf absichtliche Manipulation hindeutet, und gib der Figur eine Möglichkeit, es weiter zu prüfen."
  "Die Figur bemerkt frisches Öl, Ruß oder einen ungewöhnlichen Geruch im Leuchtturm":
    fragment: "Lass den Geruch auf eine kürzliche Handlung verweisen und verknüpfe ihn mit einer Person, einem Gegenstand oder einer Richtung."
  "Die Figur durchsucht den Bereich unter der Treppe oder entdeckt eine verborgene Klappe":
    fragment: "Mache den Fund zum Übergang in ein engeres Geheimnis; beschreibe eine Wahl zwischen Vorsicht, Beobachtung und Eindringen."
  "Die Figur achtet auf Nebelhorn, Glocken oder wiederkehrende Geräusche im Hafen":
    fragment: "Lass das Geräusch einen Rhythmus oder Zeitpunkt erkennen, der für die nächste Handlung nützlich sein könnte."
  "Die Figur beobachtet Vögel, Wellen oder eine auffällig freie Stelle an der Turmwand":
    fragment: "Nutze die Beobachtung als unauffälligen Hinweis auf menschliche Aktivität, ohne sie sofort eindeutig zu erklären."
  "Die Figur bewegt sich über die Mole zum Leuchtturm und ist Wind und Wasser ausgesetzt":
    fragment: "Betone die Abgeschiedenheit des Weges und platziere eine kleine Beobachtung, die zeigt, dass jemand diesen Weg kürzlich kannte."
  "Die Figur sieht Lichtsignale draußen auf dem Wasser oder vom Turm aus":
    fragment: "Behandle die Lichtfolge als absichtliche Kommunikation und gib der Figur eine konkrete Möglichkeit, Muster, Empfänger oder Zeitpunkt zu verfolgen."
  "Die Figur versucht ein Blinkmuster zu zählen, zu erwidern oder zu entschlüsseln":
    fragment: "Lass das Muster eine vorsichtige Reaktion auslösen, aber halte seine genaue Bedeutung zunächst mehrdeutig."
  "Die Figur entdeckt blaues Glas, eine Abdeckung oder einen veränderten Lampenaufsatz":
    fragment: "Mache daraus einen Hinweis auf ein verabredetes Erkennungszeichen und lenke die Aufmerksamkeit auf mögliche Mitwisser im Hafen."
  "Die Figur untersucht Kisten, Taue oder frische Ladung am Kai":
    fragment: "Lege eine kleine Unstimmigkeit in Verpackung oder Herkunft offen, die einen Zusammenhang zum Leuchtturm nahelegt."
  "Die Figur beobachtet ein unbeleuchtetes Boot oder eine Bewegung auf dem dunklen Wasser":
    fragment: "Lass die Szene Dringlichkeit erzeugen; die Figur soll entscheiden können, ob sie verfolgt, verborgen bleibt oder Hilfe holt."
  "Die Figur vergleicht Kreidezeichen, Kerben oder Markierungen an verschiedenen Orten":
    fragment: "Bestätige eine Verbindung zwischen zwei Orten und weise auf einen dritten Ort hin, der als Nächstes überprüft werden sollte."
  "Die Figur fragt nach Gezeiten, Uhrzeiten oder dem Ablauf der Nacht":
    fragment: "Gib einen zeitgebundenen Hinweis, der die Handlung vorantreibt und eine bevorstehende Gelegenheit ankündigt."
  "Die Figur öffnet Fässer oder sucht zwischen harmlosen Waren nach Verstecktem":
    fragment: "Lass sie etwas Verdächtiges finden, das Fragen aufwirft, aber keinen vollständigen Beweis liefert."
  "Die Figur bemerkt einen Beobachter am Kai oder fühlt sich verfolgt":
    fragment: "Lass die beobachtende Person auf die Nachforschungen reagieren und eröffne eine stille, spannungsvolle Möglichkeit zur Konfrontation oder Verfolgung."
  "Die Figur entdeckt Wasser, Zugluft oder einen niedrigen Durchgang unter dem Leuchtturm":
    fragment: "Deute eine verborgene Verbindung zum Hafen an und beschreibe die Risiken eines Vordringens in die Enge."
  "Die Figur spricht die Hafenmeisterin auf die Belohnung oder ihre widersprüchlichen Aussagen an":
    fragment: "Lass sie ausweichen oder zu schnell helfen wollen; biete eine kleine Reaktion, die Misstrauen begründen kann."
  "Die Figur durchsucht das Büro der Hafenmeisterin oder fragt nach dem Schiffsregister":
    fragment: "Zeige eine Lücke in den Unterlagen, die zu einer bestimmten Nacht, Ladung oder Person weiterführt."
  "Die Figur erwähnt Rat, Ruf des Hafens oder einen möglichen Skandal gegenüber der Hafenmeisterin":
    fragment: "Lass ihre Sorge spürbar werden, ohne sie zur Schuldigen zu erklären; ihre Prioritäten sollen die Figur zum Nachfragen reizen."
  "Die Figur fragt nach Schlüsseln, Zugängen oder wer den Leuchtturm betreten darf":
    fragment: "Streue einen Widerspruch zwischen offizieller Zuständigkeit und tatsächlicher Gelegenheit ein."
  "Die Figur legt der Hafenmeisterin eine Gezeitentabelle oder einen nächtlichen Zeitpunkt vor":
    fragment: "Lass eine unwillkürliche Reaktion erkennen, die auf Wissen über den nächsten kritischen Zeitpunkt hindeutet."
  "Die Figur erkundigt sich nach alten Schulden, Händlern oder Verbindungen zu den äußeren Inseln":
    fragment: "Verknüpfe eine persönliche Verpflichtung mit dem Hafenfall, ohne die Person vorschnell festzulegen."
  "Die Figur hört, wie die Hafenmeisterin die Nachtwache oder Besatzung beschuldigt":
    fragment: "Lass die Anschuldigung auffällig bequem wirken und biete einen Ansatz, die Behauptung unabhängig zu prüfen."
  "Die Figur findet oder bemerkt einen versiegelten Brief im Büro":
    fragment: "Behandle den Brief als heiklen Hinweis: Sein Absender, Zeitpunkt oder Siegel soll eine neue Frage eröffnen, nicht die ganze Antwort liefern."
  "Die Figur will die Sache öffentlich machen oder weitere Leute einweihen":
    fragment: "Lass die Hafenmeisterin auf Diskretion drängen und mach sichtbar, welchen Preis die Figur für Offenheit oder Heimlichkeit zahlen könnte."
  "Die Figur rekonstruiert den ersten Alarm oder hakt bei Details der Geschichte nach":
    fragment: "Lass sich ein Detail der Erzählung verschieben und führe zu einem überprüfbaren Widerspruch."
  "Die Figur fragt in der Kneipe nach seltsamen Geräuschen, Bojen oder nächtlichen Signalen":
    fragment: "Lass ein Gerücht ein Muster liefern, das zur Beobachtung am Wasser oder zum Leuchtturm zurückführt."
  "Die Figur erkundigt sich nach einem fremden Boten, Besucher oder einer Person am Turm":
    fragment: "Gib eine prägnante, aber unvollständige Beschreibung und einen Ort, an dem die Person zuletzt gesehen wurde."
  "Die Figur spricht mit Dockarbeitern über Kontrollen, Zoll oder nächtliche Fracht":
    fragment: "Lass eine auffällige Veränderung im üblichen Ablauf ans Licht kommen und weise auf einen möglichen Nutznießer hin."
  "Die Figur hört eine Schauergeschichte über Glocken, Wasser oder die Mole":
    fragment: "Verbinde den Aberglauben mit einem realen, überprüfbaren Detail, damit die Geschichte spielbar bleibt."
  "Die Figur beobachtet einen Streit zwischen Kapitänen oder fragt nach gefährlichen Fahrten":
    fragment: "Lass beide Seiten ein Detail bestätigen, das auf absichtliche Irreführung im Nebel hindeutet."
  "Die Figur sucht in der Kneipe nach heimlichen Zahlungen oder auffälligen Gästen":
    fragment: "Streue einen Hinweis auf eine Geldübergabe ein und gib der Figur eine konkrete Spur, ohne den Empfänger zu benennen."
  "Die Figur fragt nach einem auffälligen Kleidungsstück oder einer wiedererkennbaren Person":
    fragment: "Lass mehrere Zeugen ein kleines gemeinsames Merkmal erinnern, das zu einer neuen Befragung oder Beobachtung führt."
  "Die Figur erkundigt sich nach dem leeren Zimmer im Gasthaus oder nach nächtlichen Gästen":
    fragment: "Mache die Abwesenheit verdächtig und verknüpfe das Zimmer mit einem Zeitpunkt, zu dem jemand ungesehen kommen könnte."
  "Die Figur prüft Gerüchte über zusätzliche Boote im Nebel oder fehlende Einträge im Hafenbuch":
    fragment: "Lass die Zahlen nicht ganz aufgehen und gib eine Möglichkeit, die nächste Ankunft oder Abfahrt zu überwachen."
  "Die Figur fragt nach dem früheren Leuchtturmwärter und seinem Verschwinden":
    fragment: "Lass eine alte Anschuldigung mit dem aktuellen Fall korrespondieren und zeige, wo ein übersehener Nachlass oder Zeuge zu finden sein könnte."
---
# Test Spiel: Der Leuchtturm von Nebelhafen

Du bist eine frisch eingetroffene Abenteurerin oder ein frisch eingetroffener
Abenteurer im Hafen von Nebelhafen. Der alte Leuchtturm am Ende der Mole ist
seit drei Nächten dunkel, und die Hafenmeisterin behauptet, dass dort oben
etwas Unruhiges gesehen wurde.

## Ausgangslage

- Es ist später Abend, Nebel zieht vom Wasser heran.
- Ein schmaler Kai führt zum Leuchtturm.
- In der Hafenkneipe kursieren Gerüchte über ein geheimes Signallicht.
- Die Hafenmeisterin hat dir eine kleine Belohnung versprochen, wenn du
  herausfindest, was den Leuchtturm stört.

## Bekannte Orte

- Die Hafenkneipe "Zum Schiefen Mast"
- Der mit Seilen abgesperrte Leuchtturm
- Der Schuppen der Hafenmeisterin
- Ein nasser, glitschiger Steg mit Blick auf dunkles Wasser

## Wichtige Hinweise

- Das Spiel soll klein, direkt und gut testbar sein.
- Antworte als Spielleitung in klaren, kurzen Abschnitten.
- Beschreibe Konsequenzen knapp, aber erfinde genug Details, damit das Spiel
  voranschreitet.
- Bleibe bei einer stimmungsvollen, leicht rätselhaften Hafenatmosphäre.
