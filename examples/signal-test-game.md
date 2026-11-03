---
initial-situation:
  world-state:
    location: Kai
  setting: |
    You are an adventurer newly arrived at Nebelhafen harbor. The old lighthouse
    at the end of the breakwater has been dark for three nights, and the harbor
    master claims that something unsettling was seen up there.

    - It is late evening, and fog is rolling in from the water.
    - A narrow quay leads to the lighthouse.
    - Rumors of a secret signal light circulate in the harbor tavern.
    - You are on the quay at Nebelhafen.
    - The harbor master tells you that the old lighthouse has been dark for three
      nights. She promises you a small reward if you find out what is wrong.

locations:
  Kai: "Working quay at Nebelhafen, with bollards, ropes and a view of the breakwater."
  Leuchtturm: "Old stone tower at the end of the breakwater; its signal light has been dark for three nights; cordoned off with ropes."
  Hafenkneipe: "Name: \"The Crooked Mast\". A rough waterfront tavern where sailors exchange rumors."
  Schuppen der Hafenmeisterin: "A secluded storage shed near the quay, managed by the harbor master."
  Steg: "A narrow, wet and slippery wooden jetty above the dark harbor water."
flags:
  war-im-leuchtturm: false
  hat-hinweis: false
  hafenmeisterin-misstrauisch: false
signals:
  betritt-leuchtturm:
    event-check: >
      The character actually entered the lighthouse during this exchange; intention, failed attempts and viewing it from outside do not count.
    condition: {war-im-leuchtturm: false}
    triggers:
      - set-flag:
          war-im-leuchtturm: true
  entdeckt-hinweis:
    event-check: >
      The character found or recognized a specific clue about the harbor case during this exchange; searching alone does not count.
    condition: {hat-hinweis: false}
    triggers:
      - set-flag:
          hat-hinweis: true
  verunsichert-hafenmeisterin:
    event-check: >
      The harbor master actually became suspicious during this exchange due to the character’s behavior; a neutral conversation does not count.
    condition: {hafenmeisterin-misstrauisch: false}
    triggers:
      - set-flag:
          hafenmeisterin-misstrauisch: true
  beruhigt-hafenmeisterin:
    event-check: >
      The previously suspicious harbor master actually calmed down during this exchange due to the character’s behavior; an unsuccessful attempt does not count.
    condition: {hafenmeisterin-misstrauisch: true}
    triggers:
      - set-flag:
          hafenmeisterin-misstrauisch: false
runtime-fragments:
  "The character is at the lighthouse":
    "The character examines the dark lighthouse from outside and looks for a way in": >
      Reveal a tangible irregularity that invites entry or a cautious decision.
    "The character stands before the barred door and examines its lock, chain or marks": >
      Make clear that the entrance was used recently without revealing the solution; offer a plausible next lead.
    "The character crosses the breakwater toward the lighthouse, exposed to wind and water": >
      Emphasize the isolation and add a small observation suggesting someone knew this route recently.
    "The character discovers water, a draft or a low passage beneath the lighthouse": >
      Hint at a hidden connection to the harbor and describe the risks of entering the confined space.
    "The character asks about keys, entrances or who is allowed inside the lighthouse": >
      Introduce a discrepancy between official responsibility and actual opportunity.
  "The character actually enters the lighthouse or is inside it":
    emit-text: >
      The character is inside the lighthouse. Describe the damp stone floor, the
      narrow spiral staircase and the dark lantern room. Entry can be through
      the door or another plausible route. Approaching or looking from outside
      alone does not count as entering.
  "The character discovers, examines or reads a clue about the harbor case":
    emit-text: >
      Give a tangible clue about the failed signal light or suspicious harbor
      activity, such as a note, distinctive trace, marking or witness statement.
      Let the character derive a simple next step. A general search without a
      discovery does not yet count as a discovered clue.
  "The character explores the lighthouse interior":
    "The character looks around inside the lighthouse": >
      Describe the damp walls, stairs and lantern room, and offer a simple lead
      for further exploration.
    "The character discovers or examines a clue inside the lighthouse":
      emit-text: >
        The character recognizes a distinctive trace, note or marking and gets
        a simple lead to follow it further.
    "The character examines the stairs, lantern room or lamp": >
      Keep the danger of wet masonry present. Show a distinctive detail such as
      fresh oil, soot, an altered cover or scratched marks that suggests recent
      tampering and can be investigated further.
    "The character searches under the stairs, along the walls or in a passage": >
      Show a hidden hatch, a note or signs of recent use. Offer a choice between
      cautious examination and pressing onward.
  "The character explores the breakwater":
    "The character follows wet tracks in the tower or on the breakwater": >
      Lead the trail to a new place or detail, leaving open whether it belongs to helpers, smugglers or a false lead.
    "The character hears a frightening tale about bells, water or the breakwater": >
      Connect the superstition to a real, verifiable detail to keep the story playable.
  "The character observes signals at the harbor":
    "The character listens for a foghorn, bells or recurring sounds in the harbor": >
      Let the sound reveal a rhythm or timing that could help the next action.
    "The character sees light signals on the water or from the tower": >
      Treat the lights as deliberate communication and give the character a concrete way to investigate their pattern, recipient or timing.
    "The character tries to count, answer or decode a flashing pattern": >
      Let the pattern prompt a cautious reaction, but keep its precise meaning ambiguous for now.
  "The character investigates the harbor and cargo":
    "The character examines crates, ropes or fresh cargo on the quay": >
      Reveal a small discrepancy in packaging or origin that suggests a connection to the lighthouse.
    "The character watches an unlit boat or movement on the dark water": >
      Create urgency; let the character decide whether to pursue, remain hidden or seek help.
    "The character compares chalk signs, notches or markings at different locations": >
      Confirm a connection between two locations and point to a third that should be checked next.
    "The character asks about tides, times or the course of the night": >
      Give a time-sensitive clue that moves the story forward and announces an approaching opportunity.
    "The character waits for suspicious movement on the water": >
      During a brief slack tide, an unlit boat glides past the breakwater.
      The character can follow it, observe unnoticed or fetch the harbor guard.
    "The character opens barrels or looks for hidden things among harmless goods": >
      Let them find something suspicious that raises questions but provides no conclusive proof.
    "The character notices an observer on the quay or feels followed": >
      Let the observer react to the investigation and offer a quiet, tense opportunity for confrontation or pursuit.
    "The character asks about old debts, traders or connections to the outer islands": >
      Connect a personal obligation to the harbor case without prematurely identifying the person as guilty.
    "The character checks rumors of extra boats in the fog or missing harbor ledger entries": >
      Let the numbers fail to add up and offer a way to monitor the next arrival or departure.
  "The character searches the harbor master's office or asks for the ship register": >
    Show a gap in the records leading to a particular night, cargo or person.
  "The character searches the harbor master's office":
    "The character finds or notices a sealed letter": >
      Treat the letter as a sensitive clue: its sender, timing or seal should raise a new question rather than provide the entire answer.
  "The character hears the harbor master accuse the night watch or crew": >
    Make the accusation seem suspiciously convenient and offer a way to check the claim independently.
  "The character talks to the harbor master":
    "The character mentions the reward or contradictory statements": >
      Let her evade the question or offer help too quickly; provide a small reaction that could justify suspicion.
    "The character mentions the council, the harbor's reputation or a possible scandal": >
      Show her concern without declaring her guilty; let her priorities invite further questions.
    "The character presents a tide table or a time during the night": >
      Reveal an involuntary reaction suggesting knowledge of the next critical time.
    "The character wants to publicize the matter or involve more people": >
      Let the harbor master press for discretion and show the possible cost of openness or secrecy.
    "The character reconstructs the first alarm or probes details of the story": >
      Let a detail of the account shift and lead to a verifiable contradiction.
  "The character addresses the harbor master skeptically, contradicts or accuses her":
    emit-text: >
      The harbor master reacts with suspicion and reserve. Let her ask why she
      is being accused. No particular discovery or evidence is needed; a
      factual conversation alone does not trigger suspicion.
  "The character reassures or apologizes to the harbor master or kindly offers help":
    emit-text: >
      The harbor master relaxes and becomes willing to talk again. Her response
      depends on the character's conciliatory behavior, not on a particular
      light code or piece of evidence.
  "The character looks for clues in the harbor tavern":
    "The character asks about strange noises, buoys or nighttime signals": >
      Let a rumor provide a pattern leading back to observations on the water or at the lighthouse.
    "The character asks about a stranger, messenger, visitor or person at the tower": >
      Give a concise but incomplete description and a place where the person was last seen.
    "The character talks to dockworkers about inspections, customs or nighttime cargo": >
      Reveal a noticeable change in the usual routine and point to a possible beneficiary.
    "The character watches an argument between captains or asks about dangerous voyages": >
      Let both sides confirm a detail suggesting deliberate deception in the fog.
    "The character looks for secret payments or conspicuous guests": >
      Introduce a clue about a handover of money and provide a concrete lead without naming the recipient.
    "The character asks about a distinctive garment or recognizable person": >
      Let several witnesses recall a small shared detail leading to another inquiry or observation.
    "The character asks about the empty inn room or nighttime guests": >
      Make the absence suspicious and link the room to a time when someone could arrive unseen.
  "The character researches the lighthouse's history":
    "The character asks about its disappearance": >
      Connect an old accusation to the current case and show where an overlooked estate or witness might be found.
---
# Signal Test: The Nebelhafen Lighthouse

## Your Role

You are a concise, responsive game master. Continue the adventure using the
scenario and the conversation so far.

## Important Notes

- Keep the game small, direct, and easy to test.
- Respond as the game master in clear, short paragraphs.
- Describe consequences concisely, but invent enough detail for the game
  to move forward.
- Maintain an atmospheric, slightly mysterious harbor setting.
