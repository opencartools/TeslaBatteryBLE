# VIN prompt

## Sub-features

The standing "VIN needed" card under the car hero, the blocking VIN prompt raised by refresh/wake/quick actions, the shared field with its hint and Tesla-app link, the verdicts (partial, matching, mismatch), and the replay of the action that raised the prompt.

## How to get to it (user POV)

Open a car with no stored VIN, then pull to refresh, wake it, or use a quick action. The prompt names the car and asks for the 17-character VIN. Saving replays the blocked action; Cancel leaves the car alone. The card is visible before any action.

## Driving it with Paparazzi

- `VinPromptSnapshots` — `vin-prompt_partial_{light,dark}`, `vin-prompt_valid_{light,dark}`, `vin-prompt_mismatch_{light,dark}`.
- The car-screen card is rendered with the car cards; look for the `vin-card_needed_*` state.

The field and the card share one hint composable, so a change must be checked in both places.

## Gotchas

- Background reads deliberately do not prompt; only user intent does. Do not add a snapshot that implies otherwise.
- The hint row is the link: the open-in-new icon sits at the start of the sentence and the whole row opens the Tesla app. Check the icon aligns with the paragraph above it, not with the field box.
- A valid VIN shows the saved verdict instead of the hint; the link is expected to disappear there.
- The accepted copy is specific; compare it verbatim, not by meaning.
