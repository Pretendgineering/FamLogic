# Connected design: profiles, logs and rules

(A developer document.) FamLogic is one connected set of notes, not a set of separate apps. Every
module follows the same shape, so everything is aware of everything else and stays reliable.

## The shape

1. **Profiles: the things the family looks after.** People (Who's Who), the home (Homestead),
   cars (Garage), pets (Menagerie). Each is a note in the vault with its details in front matter
   and plain sections.
2. **Logs: what happened to them.** Dated entries: a chore's done history, a fuel fill-up, a vet
   visit, a receipt. Prefer an existing log over a new one: Homestead's log *is* its chores'
   history.
3. **Rules: profiles and logs turn into actions in other modules.** Ticking "furnace" makes a
   repeating Upkeep chore; a bin day makes a chore and a Datebook event; an oil-change interval
   and the logged mileage make a reminder. Rules run when a profile is saved (and, for
   mileage-style rules, when a log is added), and are **idempotent**: saving again makes nothing
   new; unticking retires what the rule made.
4. **One input, everywhere it belongs.** A receipt (by voice or photo) becomes a Ledger
   transaction *and* a Garage fuel entry or Pantry stock; a vet visit is a Menagerie log entry,
   the next shot in Datebook and a bill in Ledger. Steward does the sorting; the server's rules
   do the rest.

## The links that make it reliable

Everything a rule or input makes carries two front-matter fields:

- `for: "[[Home]]"`: the thing it's about (a home, a car, a pet, a person). Lets any module and
  Steward see what belongs to what.
- `source: home:furnace-filter`: the rule (or input) that made it. Lets the rule find, update and
  retire exactly its own items, never anyone else's, and never duplicate them.

Chores and calendar events support both today (`Chore.For/Source`, `CalEvent.Source`,
`EventInput.For/Source`). New kinds of item get them too.

## Rules of thumb

- **Simple first:** reuse chores, events, lists and notes; add a new kind of thing only when none
  fits.
- **Automatic, assigned to anyone:** rule-made chores are created without asking (the family chose
  the profile) and are for anyone unless a person is named.
- **Undoable:** anything Steward makes goes through Steward's undo journal; anything a rule makes
  is retired by changing the profile back.
- **Every module gets its UI everywhere** (see the Style guide): app tile and screen, console page
  and Home panel, Steward tools and page context, the vault guide, setup question.

## Built so far

- Who's Who (people's profiles; 0.40).
- Homestead (the home's profile → Upkeep chores and Datebook bin days; 0.42). The template for
  Garage and Menagerie.
- Huddle (family meetings as notes; jobs handed out in the meeting go onto the Docket; 0.47).
