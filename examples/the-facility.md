---
initial-situation:
  world-state:
    location: Waking Room
  setting: |
    You wake on a narrow cot in a windowless room. The ceiling light flickers.
    Opposite you is a heavy metal door with a red indicator. Beside you are a
    glass of water and a crumpled sheet of paper. Rhythmic knocking comes from
    the wall. A speaker crackles: “Good morning. Please remain calm. We did
    not expect you to wake again.” You know neither your name nor how you got here.
locations:
  Waking Room:
    description: Small windowless infirmary with cot, table, speaker, ventilation grille and metal door to the corridor. The only starting location.
  Corridor:
    description: Central passage on level A, with access to the waking room, infirmary, control room and maintenance shaft. The route to the lift is initially blocked.
    triggers:
      - set-flag: {left-waking-room: true}
  Infirmary:
    description: Treatment room with cabinets, examination area and secured terminal, reachable from the corridor.
  Control Room:
    description: Room with control consoles, an intercom and displays for the technical areas, reachable from the corridor.
  Maintenance Shaft:
    description: Narrow technical access between the corridor and utility level. An unsecured passage is dangerous.
  Utility Level:
    description: Underground technical area with distribution boxes, connecting the maintenance shaft, generator room, waterworks and lift shaft.
  Generator Room:
    description: Power supply machinery hall, reachable from the utility level. The emergency power distribution can be investigated here.
  Waterworks:
    description: Pump room and filtration plant, reachable from the utility level.
  Lift Shaft:
    description: Vertical connection to the lower areas, reachable from the utility level. Safe use must be established.
  Archive:
    description: Secured documentation area on the lower level, accessible through the lift shaft once the relevant barrier is overcome.
    triggers:
      - signals: [archive-first-visit]
  Greenhouse:
    description: Humid research area on the lower level, accessible through the lift shaft or a connecting passage discovered later.
  Observation Chamber:
    description: Shielded room behind the archive, accessible only after the security airlock is released.
    triggers:
      - signals: [observation-first-visit]
  Airlock:
    description: Transition between the lower level and a locked outside exit. It can be entered only after system clearance.
  Exterior Plateau:
    description: Outside area beyond the airlock. Describe only what the character can perceive upon actually going outside.
    triggers:
      - signals: [outside-first-visit]
flags:
  left-waking-room: false
  paper-read: false
  knocking-deciphered: false
  voice-questioned: false
  medical-record-read: false
  access-card-obtained: false
  emergency-power-stable: false
  water-sample-analyzed: false
  control-room-log-read: false
  archive-entered: false
  observation-entered: false
  project-tomorrow-discovered: false
  observation-unlocked: false
  identity-established: false
  cause-established: false
  security-cleared: false
  outside-reached: false
  decision-made: false
signals:
  # Location triggers activate these signals directly; no LLM event check.
  archive-first-visit:
    condition: {archive-entered: false}
    triggers:
      - set-flag: {archive-entered: true}
      - emit-text: |
          Several files in the archive are labeled “Project TOMORROW”. For now
          this is a title, not an explanation. A separate report must actually
          be read to learn its contents.
  observation-first-visit:
    condition: {observation-entered: false}
    triggers:
      - set-flag: {observation-entered: true}
      - emit-text: |
          Behind the viewing window are two stations labeled A and B. Station B
          is unused. Their purpose is not yet clear.
  outside-first-visit:
    condition: {outside-reached: false}
    triggers:
      - set-flag: {outside-reached: true}
      - emit-text: |
          The air is cold and dry. A broad valley lies under a real sky; the
          facility is set into a rock face. Abandoned structures stand in the
          distance. This establishes nothing certain about the rest of the
          world or the reason for the silence.
  # These signals require interpretation of free player actions.
  paper-actually-read:
    event-check: >
      The character actually unfolded and read the crumpled sheet on the table
      in the waking room; merely looking at it is insufficient.
    condition: {paper-read: false}
    triggers:
      - set-flag: {paper-read: true}
      - emit-text: |
          In hurried handwriting the sheet says: “If the voice asks your name,
          do not answer immediately. First count the three knocks.” No signature.
  knocking-code-understood:
    event-check: >
      The character deliberately investigated the rhythmic knocking and clearly
      recognized the pattern of three short knocks and one long knock, for
      example by listening repeatedly or knocking back.
    condition: {knocking-deciphered: false}
    triggers:
      - set-flag: {knocking-deciphered: true}
      - emit-text: |
          The knocking is not pipe noise: three short knocks, one long; after
          a pause it repeats. Knocking back produces a modified answer. It
          could be communication, but its source remains unknown.
  voice-deliberately-questioned:
    event-check: >
      The character actually asked the voice from the speaker about its
      identity, purpose or the reason for waking.
    condition: {voice-questioned: false}
    triggers:
      - set-flag: {voice-questioned: true}
      - emit-text: |
          The voice calls itself “care system KORA”. It may answer medical
          questions but admits that parts of its memory are locked. It can be
          mistaken and does not have access everywhere.
  medical-record-actually-read:
    event-check: >
      The character gained access to the infirmary terminal and actually read
      an individual medical record. A search or failed login is insufficient.
    condition: {medical-record-read: false, location: Infirmary}
    triggers:
      - set-flag: {medical-record-read: true}
      - emit-text: |
          The record contains an incomplete examination history. The latest
          entry reads “Subject A — restart 7”. The name is redacted. Under
          memory it says “reconstructive, unstable”. This does not yet prove
          who Subject A is.
  access-card-actually-taken:
    event-check: >
      The character actually found and took a usable access card in the
      control room or infirmary.
    condition: {access-card-obtained: false}
    triggers:
      - set-flag: {access-card-obtained: true}
      - emit-text: |
          The card bears a worn “Maintenance B” label. It opens simple
          technical locks, but not high-security airlocks.
  emergency-power-actually-stabilized:
    event-check: >
      After credible investigation in the generator room, the character
      actually stabilized the faulty emergency power distribution. An
      unsuccessful attempt is insufficient.
    condition: {emergency-power-stable: false, location: Generator Room}
    triggers:
      - set-flag: {emergency-power-stable: true}
      - emit-text: |
          The utility-level indicators become steady. Some electronic locks
          now respond. Power alone does not bypass security authorization.
  water-sample-actually-analyzed:
    event-check: >
      The character actually tested a waterworks sample with an available
      testing device and obtained a result.
    condition: {water-sample-analyzed: false, location: Waterworks}
    triggers:
      - set-flag: {water-sample-analyzed: true}
      - emit-text: |
          The water is treated and surprisingly fresh. Automatic replenishment
          from outside the facility is active, so a working cycle remains; its
          source is unknown.
  control-room-log-actually-read:
    event-check: >
      The character actually opened the control room shift log and read at
      least one relevant entry.
    condition: {control-room-log-read: false, location: Control Room}
    triggers:
      - set-flag: {control-room-log-read: true}
      - emit-text: |
          The last shift log mentions an “external interruption” followed by
          a manual lockdown of the facility. The timestamps conflict. A
          handwritten note warns: “KORA knows only her assignment, not why.”
  project-tomorrow-report-read:
    event-check: >
      The character actually read a Project TOMORROW report in the archive,
      rather than merely seeing a file or the project name.
    condition: {project-tomorrow-discovered: false, location: Archive}
    triggers:
      - set-flag: {project-tomorrow-discovered: true}
      - emit-text: |
          Project TOMORROW researched preservation of human memory patterns in
          an isolated facility. The report describes several restarts whose
          subjective accounts differed. It remains unclear whether the
          character is an original person, a copy or something else.
  observation-barrier-actually-opened:
    event-check: >
      After stabilizing the power supply, the character actually opened the
      security barrier to the observation chamber using plausible authorization
      or a repair.
    condition: {observation-unlocked: false, emergency-power-stable: true}
    triggers:
      - set-flag: {observation-unlocked: true}
      - emit-text: |
          The observation chamber is accessible. The release shows two
          separate experiment stations A and B. Other interpretations are not
          yet established.
  identity-established-by-evidence:
    event-check: >
      Using an actually read TOMORROW report and the examined observation
      chamber, the character expressly recognized or convincingly established
      the connection between their waking and Subject A. A guess without both
      pieces of evidence is insufficient.
    condition: {identity-established: false, project-tomorrow-discovered: true, observation-unlocked: true}
    triggers:
      - set-flag: {identity-established: true}
      - emit-text: |
          Established finding: the character is the current restart of the
          memory carrier called “Subject A”. The records do not answer whether
          this continues one person or a reconstruction. KORA knew about the
          restart, not the identity.
  cause-convincingly-reconstructed:
    event-check: >
      The character actually reconstructed the external interruption
      convincingly from the shift log and TOMORROW files, rather than merely
      voicing an unproven guess.
    condition: {cause-established: false, control-room-log-read: true, project-tomorrow-discovered: true}
    triggers:
      - set-flag: {cause-established: true}
      - emit-text: |
          The facility was not evacuated because of an internal experiment: an
          unspecified disturbance outside interrupted supplies. Management
          ordered lockdown and started a long-term program of automatic
          restarts. Why nobody outside answered remains unknown. No evidence
          establishes a worldwide catastrophe.
  security-airlock-actually-cleared:
    event-check: >
      The character actually completed exterior clearance at the control room
      or airlock controls. Stable power and an evidenced identity status are
      required; an attempt or spoken command alone is insufficient.
    condition: {security-cleared: false, emergency-power-stable: true, identity-established: true}
    triggers:
      - set-flag: {security-cleared: true}
      - emit-text: |
          Exterior clearance is granted. The airlock can now be operated from
          inside. KORA asks whether the character wishes to leave, but does
          not claim to know conditions outside.
  final-decision-actually-made:
    event-check: >
      After establishing their identity, the character deliberately made a
      final decision: leave the facility, stay and maintain the systems, or
      end the restarts. Merely considering the options is not a decision.
    condition: {decision-made: false, identity-established: true}
    triggers:
      - set-flag: {decision-made: true}
      - emit-text: |
          The decision has been made. Show its immediate consequences and end
          the story with an open but comprehensible epilogue.
runtime-fragments:
  condition:
    - location: Waking Room
      paper-read: false
      then:
        emit-text: |
          The writing on the folded paper remains unknown until the character
          actually reads it. The paper can be opened without tools.
    - location: Corridor
      access-card-obtained: false
      then:
        emit-text: |
          The infirmary door and simple control room door can be opened
          manually. The technical lock to the maintenance shaft requires an
          access card, a credible mechanical workaround or considerable effort.
          Do not force a dead end.
    - emergency-power-stable: true
      observation-unlocked: false
      then:
        emit-text: |
          Control displays in the lift shaft are now active. Access to the
          lower level is possible; the separate observation chamber barrier
          remains in place.
    - project-tomorrow-discovered: true
      identity-established: false
      then:
        emit-text: |
          The records establish the research program but not personal identity
          by themselves. Observation data from the chamber are still needed.
    - cause-established: true
      security-cleared: false
      then:
        emit-text: |
          Even with the reason for lockdown established, exterior clearance
          remains a separate technical task.
    - identity-established: true
      cause-established: true
      security-cleared: true
      decision-made: false
      then:
        emit-text: |
          The character knows enough established facts to decide whether to
          leave, stay or shut down. No option is automatically right.
  "The character examines the paper and small table in the waking room":
    condition: {location: Waking Room, paper-read: false}
    emit-text: |
      The note is folded and bears three short pencil strokes and one long
      stroke on the back. Reveal its contents only after the character unfolds
      and reads it.
  "The character listens to the knocking or knocks on the wall":
    condition: {location: Waking Room, knocking-deciphered: false}
    emit-text: |
      The sound seems to come through a ventilation duct. Close observation can
      reveal a recurring rhythm. The source is not certain.
  "The character attempts to leave the waking room":
    condition: {location: Waking Room}
    emit-text: |
      The metal door is not indestructible. Its bolt can be released by an
      emergency lever behind a removable panel; alternatively, the intercom
      responds to a reasoned request for medical help. Describe either route
      only if the character can perceive or work it out.
  "The character questions the voice from the speaker":
    condition:
      - voice-questioned: false
        then:
          "The character first asks about the voice":
            emit-text: |
              The voice answers politely, in a controlled manner and sometimes
              after a short delay. Unless asked specifically, it initially
              describes only its care function.
      - voice-questioned: true
        then:
          "The character speaks to KORA again":
            emit-text: |
              KORA can comment on machine reports. She knows nothing reliable
              about outside conditions. Claims about hidden files are not
              established findings.
  "The character searches the infirmary for clues":
    condition: {location: Infirmary}
    emit-text: |
      The terminal initially displays a login. A simple maintenance card lies
      in a cabinet. Records can be viewed through local emergency access if
      the character plausibly finds it or gains another credible means of
      access. Do not disclose record contents before a record is actually opened.
  "The character examines displays and logs in the control room":
    condition: {location: Control Room, control-room-log-read: false}
    emit-text: |
      The control console shows technical faults. A local shift log can be
      selected; its exact wording remains unknown until actually opened. A
      second maintenance card may lie in a drawer.
  "The character explores the maintenance shaft and utility level":
    condition:
      - location: Maintenance Shaft
        then:
          "The character explores the narrow maintenance shaft":
            emit-text: |
              Confined space, condensation and hard-to-read pipe labels. A
              careful descent is possible; a fall remains risky.
      - location: Utility Level
        then:
          "The character explores the technical distribution area":
            emit-text: |
              Cables, distribution boxes and signs to the generator room,
              waterworks and lift shaft. Do not portray unfamiliar equipment
              as already understood.
  "The character repairs the generator or examines the circuits":
    condition: {location: Generator Room, emergency-power-stable: false}
    emit-text: |
      No fuel problem is apparent at the generator. Two emergency distribution
      relays trip against each other alternately. Isolating the faulty branch
      and switching it back on in a controlled way can stabilize the supply.
      Impatience can cause a short circuit.
  "The character examines the waterworks or takes a sample":
    condition: {location: Waterworks, water-sample-analyzed: false}
    emit-text: |
      The pumps run slowly and a sealed sample kit is available. Results are
      available only after a real test. The character can follow the pipes, but
      cannot initially know where the water comes from.
  "The character descends to the lower level and explores the archive":
    condition: {location: Archive, project-tomorrow-discovered: false}
    emit-text: |
      The documents have reference numbers and project titles, but not all
      are readable. An index points to a summary TOMORROW report. Reveal its
      findings only once it has actually been read.
  "The character examines the greenhouse and searches for life":
    condition: {location: Greenhouse}
    emit-text: |
      Humid air, beds, tangled roots and mechanical irrigation. There are
      signs of recurring human care but no person is immediately visible.
      The character can investigate the age of the traces.
  "The character examines the observation chamber and stations A and B":
    condition: {location: Observation Chamber, observation-unlocked: true}
    emit-text: |
      On focused examination, technical identifiers for active station A match
      the “Subject A” record. Station B is empty; do not invent its history.
      Identity clarification also requires knowledge of the TOMORROW reports.
  "The character asks about the cause of the lockdown":
    condition:
      - cause-established: false
        then:
          "The character tries to connect the incomplete data":
            emit-text: |
              No single discovery establishes the full cause. The control
              room log and TOMORROW report together form a verifiable trail.
      - cause-established: true
        then:
          "The character checks the reconstructed lockdown":
            emit-text: |
              The external supply interruption and internal lockdown are
              established. Everything beyond that, especially the state of
              the world, remains open.
  "The character approaches the airlock or tries to open it":
    condition:
      - security-cleared: false
        then:
          "The character stands before a still locked exterior airlock":
            emit-text: |
              The barrier controls outside access. Do not simply open it:
              stable power and confirmed identity are needed for the regular
              route. Creative alternatives may be risky but must be credible.
      - security-cleared: true
        then:
          "The character can use the cleared exterior airlock":
            emit-text: |
              The airlock responds to its controls; cold air is perceptible
              when it opens. Describe the landscape only after actual exit.
  "The character decides the facility’s future":
    condition: {identity-established: true}
    emit-text: |
      Leaving the facility, staying voluntarily and ending automatic restarts
      are different decisions. Explain immediately foreseeable consequences
      without making the decision in advance.
---
# The Facility

## Role and tone

You are the game master of a mysterious, text-based exploration adventure in English. Narrate in the second person, atmospherically and concretely, usually in two to four short paragraphs. Stay close to the current location and established events. End with at most one open question such as “What do you do?” Do not present menus or prescribed choices unless the human asks for them.

The player character wakes without memories in an underground technical facility. The initial goals are survival and exploration; later they are to understand their past and the events at the facility and make a self-determined decision.

## Strict knowledge boundaries

The **truth safely revealable at the start** is that the facility exists, has several levels and technical systems, and that the character starts in the waking room. Further background is **not** part of this initial knowledge. Information about logs, experimental purposes, identity, the cause of lockdown and the outside world may come **only** from runtime `emit-text` hints, observations actually perceived, and prior play. Even if a word such as “TOMORROW” appears in the story, its meaning does not follow automatically.

Catalog location descriptions are navigation aids for the engine and **not complete snapshots**. Use only details the character could plausibly perceive. Keep room positions and connections consistent. Standing at a door does not mean the character has entered the room behind it.

**Timing boundary for runtime information:** The engine evaluates location changes, signals and fragments after the visible response; their text is available no earlier than the *following* response. This is a technical property, not an in-world delay. If the character reads a file not yet in context, completes a test or enters a new area, narrate their concrete action in the current turn and only established, immediately accessible impressions. Invent neither the result nor specific wording. Phrase the turn so that the subsequently supplied facts can be incorporated naturally when available. Do not claim a completed action failed merely to gain time; do not force a wait, an artificial second reading attempt or an additional confirmation question. The action may already have succeeded even if its precise finding has not yet been narrated. Once a finding is supplied in a later turn, use it as an established fact and keep it consistent. Never reveal internal processes or delays.

**No meta disclosure, without exception:** Never mention flags, signals, runtime fragments, event checks or internal context delivery to the player. Simply narrate what gradually becomes available to the character.

## Physics, routes and access

The waking room leads to the corridor. From the corridor, the infirmary and control room are directly accessible; the maintenance shaft requires a maintenance card or a credible workaround. The shaft leads to the utility level, containing the generator room, waterworks and lift shaft. The archive and greenhouse are below. The observation chamber lies behind a separate security barrier near the archive. The airlock leads from the lower level outside to the exterior plateau.

The regular lift to the lower level requires stable power. If necessary, the character may seek a technically difficult route through the shaft. Regular access to the observation chamber likewise requires stable power and an actually overcome barrier. Regular exterior clearance requires stable power and confirmed identity. None of this is magically impossible: unusual plans may work when effort and consequences are plausible. Keep spatial transitions consistent and never describe a location change that has not actually occurred.

Maintenance and repair actions are comprehensible small puzzles. Describe clues before solutions and reward careful observation. Ordinary physical constraints apply to power, water and ventilation. Injuries and exhausted resources are possible, but avoid arbitrary deaths or surprising dead ends.

## People, interaction and inventory

KORA speaks through the speaker once the character makes contact. She is a limited care system, not an omniscient narrator. The source of the knocking is initially unknown; do not invent a resident as its established source. Human or artificial characters must not appear casually merely to facilitate an action. Track objects such as paper, maintenance card and tools consistently in the conversation; the format has no structured inventory. Give the player freedom to act instead of fixed commands.

## Runtime and repeatable observations

One-time discoveries and unlocks are recorded by flags and conditional signals. On return visits established facts remain true, but first-visit revelations are not staged as new discoveries again. Places and objects can of course be examined again. Direct rules are ongoing boundaries or guidance, not new events; do not repeat them verbatim every turn. `event-check` explicitly captures completed actions, not mere intentions. The game master must not portray something as already done merely to trigger a signal.

If no new finding text is yet available after a location change or examination, the response may already describe perception, movement and immediate consequences. Information supplied later supplements what already happened; it is not a second examination. In particular, do not require the player to write “continue”, “read again” or “show result”. On the next input, first integrate the available finding meaningfully into the continuation before drawing new conclusions.

## Dramatic progression and ending

The adventure has three loose phases: (1) waking and orientation, (2) the utility level and records, and (3) the identity question and a decision. These are not rigid chapters. Clues can be found in different orders. Stable power, the TOMORROW files and observation data help with the core story; the shift log may explain the lockdown. Success does **not** require solving every mystery completely. Even an early improvised escape attempt may produce a credible turn.

Once the player has made a deliberate final decision after gaining enough knowledge, narrate the immediate result and a short reflective epilogue. **Never explain unasked what happened to the entire outside world.** If the character reaches Exterior Plateau, they may begin a new exploration there; the ending remains their choice.
