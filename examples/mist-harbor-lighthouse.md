---
initial-situation:
  world-state:
    location: Quay
  setting: |
    You are an adventurer newly arrived at Mist Harbor harbor. The old lighthouse
    at the end of the breakwater has been dark for three nights, and the harbor
    master claims that something unsettling was seen up there.

    - It is late evening, and fog is rolling in from the water.
    - A narrow quay leads to the lighthouse.
    - Rumors of a secret signal light circulate in the harbor tavern.
    - You are on the quay at Mist Harbor.
    - The harbor master tells you that the old lighthouse has been dark for three
      nights. She promises you a small reward if you find out what is wrong.

locations:
  Quay: "Working quay at Mist Harbor, with bollards, ropes and a view of the breakwater."
  Lighthouse: "Old stone tower at the end of the breakwater; its signal light has been dark for three nights; cordoned off with ropes."
  Harbor Tavern: "Name: \"The Crooked Mast\". A rough waterfront tavern where sailors exchange rumors."
  Harbor Master’s Shed: "A secluded storage shed near the quay, managed by the harbor master."
  Jetty: "A narrow, wet and slippery wooden jetty above the dark harbor water."
flags:
  visited-lighthouse: false
  has-tide-sketch: false
  knows-tide-window: false
  knows-light-code: false
  harbor-master-suspicious: false
signals:
  enters-lighthouse:
    event-check: The character actually entered the interior of the lighthouse.
    triggers:
      - set-flag:
          visited-lighthouse: true
  finds-tide-sketch:
    event-check: The character has taken the half-burned sheet with the tide sketch and read its tide table.
    triggers:
      - set-flag:
          has-tide-sketch: true
          knows-tide-window: true
  deciphers-light-code:
    event-check: The character deciphered the light code as two slow flashes and one fast flash.
    triggers:
      - set-flag:
          knows-light-code: true
  alarms-harbor-master:
    event-check: The harbor master became suspicious because of the character’s behavior or statements.
    triggers:
      - set-flag:
          harbor-master-suspicious: true
  resolves-misunderstanding:
    event-check: The character resolved the misunderstanding with evidence and dispelled the harbor master’s suspicion.
    triggers:
      - set-flag:
          harbor-master-suspicious: false
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
  "The character enters the lighthouse":
    "The character releases the chain and carefully steps through the door":
      signal: enters-lighthouse
      emit-text: >
        Once the character actually enters, they see the locked lantern room
        and damp stone floor clearly.
  "The character is inside the lighthouse":
    condition:
      visited-lighthouse: true
    emit-text: >
      The character is in the cordoned-off tower; keep the danger of the wet,
      weather-beaten masonry present.
    "The character climbs the narrow spiral stairs and the wind or height becomes dangerous": >
      Create a brief dangerous situation that rewards caution and connects to a clue about the signal light.
    "The character closely examines the lantern room, lens or dark lamp": >
      Show a technical detail suggesting deliberate tampering and give the character a way to investigate further.
    "The character notices fresh oil, soot or an unusual smell": >
      Let the smell point to a recent action and connect it to a person, object or direction.
    "The character searches under the stairs or discovers a hidden hatch": >
      Make the discovery lead to a deeper mystery; describe a choice between caution, observation and entering.
    "The character observes birds, waves or a conspicuously clear patch of tower wall": >
      Use the observation as a subtle clue to human activity without immediately explaining it.
    "The character discovers blue glass, a cover or an altered lamp fitting": >
      Make it a clue to an agreed recognition signal and draw attention to possible accomplices in the harbor.
    "The character examines the circles scratched next to the lantern":
      condition:
        - has-tide-sketch: false
          then:
            "The character finds a half-burned sheet in a crack in the wall":
              signal: finds-tide-sketch
              emit-text: >
                The sheet shows the same circles as the wall and a tide table
                with a handwritten code.
        - has-tide-sketch: true
          then:
            "The character compares the tide sketch with the circles":
              condition:
                knows-light-code: false
              signal: deciphers-light-code
              emit-text: >
                The markings form a code for a short light signal:
                two slow flashes followed by a fast one.
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
    "The character waits for the tide window marked on the sketch":
      condition:
        knows-tide-window: true
      emit-text: >
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
  "The character presents the lighthouse discovery to the harbor master":
    signal: alarms-harbor-master
    emit-text: >
      The harbor master initially reacts suspiciously to the accusation and
      tries to steer the conversation toward the harbor guard.
    "The character shows her the deciphered light code":
      condition:
        knows-light-code: true
      signal: resolves-misunderstanding
      emit-text: >
        Recognizing the code, she becomes less suspicious. She admits that the
        signal was previously used for safe entry into the harbor.
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
# Text Adventure: The Mist Harbor Lighthouse

## Your Role

You are a concise, responsive game master. Continue the adventure using the
scenario and the conversation so far.

## Important Notes

- Keep the game small, direct, and easy to test.
- Respond as the game master in clear, short paragraphs.
- Describe consequences concisely, but invent enough detail for the game
  to move forward.
- Maintain an atmospheric, slightly mysterious harbor setting.
