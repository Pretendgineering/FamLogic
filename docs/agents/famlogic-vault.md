# FamLogic vault: formatting guide for people and AI agents

This folder is a family's **FamLogic vault**: plain Markdown files, one note per thing
(a person, a list, a chore, an event, a week of meals, a routine). FamLogic, the family's
self-hosted hub, reads and writes it. So can Obsidian, a text editor, Syncthing, scripts
and AI agents. Follow this guide and everything you write shows up correctly in the
FamLogic apps, and nothing you write breaks what someone else wrote.

FamLogic writes this file (`AGENTS.md`) and replaces it when it updates, so don't put
your own notes in it. The same guide is in the FamLogic repository
(`docs/agents/famlogic-vault.md`) and on every FamLogic server at
`/api/v1/vault/guide`.

## The rules that matter most

1. **Never delete a note or a line you didn't create, unless someone asked you to.**
   To finish a task, tick it (`- [x]`); to retire a note, add `archived: true` to its
   front matter.
2. **Keep what you don't understand.** Leave unknown front-matter fields, comments,
   headings and text exactly as they are. People and other tools add their own.
3. **Don't edit the fields FamLogic owns** (listed under *People* below, and any field
   whose name starts with `famlogic_`). FamLogic overwrites them.
4. **Write the whole file at once.** Write to a temporary file in the same folder, then
   rename it over the old one, so nobody reads a half-written note. Re-read a note just
   before changing it; someone may have changed it since you last looked.
5. **Use the formats below exactly:** dates `YYYY-MM-DD`, times `HH:MM` (24-hour, the
   family's local time), people as `[[Name]]` links, one task per line.
6. **Ask before doing something big:** deleting, renaming people, rewriting a whole
   note, or anything a child would find surprising.

## Folders

```
FamLogic.md            the home note: links to everything (you may edit it)
AGENTS.md              this guide (FamLogic replaces it; don't edit)
People/                one note per family member (FamLogic keeps these up to date)
Lists/                 shared lists: Shopping.md, Packing - Beach trip.md
To-dos/                to-dos: one note per person (Sam.md) or shared (Family.md)
Chores/                one note per chore: Bins out.md
Calendar/              one note per event: 2026-12-05 Dentist (Sam).md
Meals/                 one note per week: 2026-W49.md
Meals/Recipes/         one note per recipe: Lasagne.md
Routines/              recurring checklists: Bedtime.md
Rhythms/               which routine is current when: School days.md
```

- File names are the note's title, plus `.md`. Avoid `/ \ : * ? " < > |` in titles
  (use ` - ` instead of a colon).
- A note's kind comes from its `type` field, not its folder, but please use the folders
  above so people can find things.
- Make a missing folder if you need it. Other folders and files are fine too; FamLogic
  ignores what it doesn't recognise.

## Every note: front matter, then the body

Every FamLogic note starts with YAML front matter between `---` lines, then a
`# Title` heading, then the body in plain Markdown:

```markdown
---
type: list
who: ["[[Sam]]"]
tags: [school]
---
# Packing - School trip

- [ ] Waterproof coat
- [ ] Packed lunch @Sam
- [x] Permission slip
```

Common fields (all optional except `type`):

| Field | Meaning | Example |
|---|---|---|
| `type` | What the note is (see below). Required. | `chore` |
| `who` | People it's for or about, as links. | `["[[Sam]]", "[[Mia]]"]` |
| `tags` | Groups and free-form tags, no `#`. | `[kids, school]` |
| `when` | Time of day it matters. | `morning`, `evening`, `16:00-18:00` |
| `due` | The date it's due. | `2026-12-02` |
| `archived` | Retired: kept, but hidden in the apps. | `true` |

- Quote links in front matter (`"[[Sam]]"`), since `[` starts a YAML list.
- Lists of values use YAML's `[a, b]` form.

## Task lines

Task lines are standard Markdown checkboxes (the same as Obsidian Tasks):

```markdown
- [ ] Milk
- [ ] Pack PE kit @Sam #school 📅 2026-12-03
- [x] Eggs ✅ 2026-11-24
```

- `- [ ]` is open, `- [x]` is done. To finish a task, change `[ ]` to `[x]`; you may add
  `✅ YYYY-MM-DD` with the date it was done.
- `@Name` assigns a task to a person. `#tag` tags it. `📅 YYYY-MM-DD` gives it a due date.
- Add new tasks at the end of their list (or the end of the right heading's section).
- Keep one task per line. Sub-tasks are indented two spaces under their task.

## Types

### `person`: People/<Name>.md (FamLogic keeps these)

```markdown
---
type: person
famlogic_id: 3f9a1c2b7d
role: younger_child
color: "#E0A030"
emoji: "🦊"
---
# Sam

Allergic to peanuts. Football on Tuesdays.
```

- FamLogic creates a note for each family member and updates its **`type`,
  `famlogic_id`, `role`, `color` and `emoji`** fields; don't change those. When someone
  is renamed, FamLogic renames their note too.
- `role` is one of `parent`, `grandparent`, `older_child`, `younger_child`, `dependent`
  (a baby or anyone without the app).
- Everything else is yours: add fields (`birthday: 2018-04-09`) and write in the body.
- Link to people as `[[Sam]]` anywhere: that's how chores, events and lists say who
  they're for.

### `list`: Lists/<Title>.md

A shared checklist (shopping, packing). The body is task lines, optionally under `##`
headings (`## Bakery`).

### `todos`: To-dos/<Name>.md

Someone's to-dos (`who: ["[[Sam]]"]`), or the family's (`Family.md`). Task lines, as
above; `when:` on a task's note or `📅` on a line says when it matters.

### `chore`: Chores/<Title>.md

```markdown
---
type: chore
who: ["[[Sam]]", "[[Mia]]"]
every: week
due: 2026-12-02
points: 2
---
# Bins out

Green and black bins to the kerb on Sunday evening.

- [x] 2026-11-24 done by [[Sam]]
- [ ] 2026-12-01
```

- `every`: `day`, `weekdays`, `week`, `2 weeks`, `month`, or days such as `mon,thu`.
- `due`: the next time it's due. `points`: optional, for families who count them.
- Below the description, one task line per time it's due. To mark it done, tick the
  line and add ` done by [[Name]]`; then add a new `- [ ]` line for the next date and
  move `due` to that date.

### `event`: Calendar/<YYYY-MM-DD> <Title>.md

```markdown
---
type: event
start: 2026-12-05T15:30
end: 2026-12-05T16:15
who: ["[[Sam]]"]
where: Smile Dental
---
# Dentist (Sam)

Bring the referral letter.
```

- `start` and `end` are `YYYY-MM-DDTHH:MM` in local time; an all-day event uses dates
  only (`start: 2026-12-24`). `end` is optional.
- Repeating events: `every:` as for chores, plus `until: YYYY-MM-DD` if they stop.

### `meals`: Meals/<YYYY-Www>.md

```markdown
---
type: meals
week: 2026-W49
---
# Meals, week 49

## Mon 2026-11-30
- Dinner: [[Lasagne]]

## Tue 2026-12-01
- Dinner: Fish tacos
- Lunch (Sam): packed
```

- One `##` heading per day, `## Ddd YYYY-MM-DD`. Lines are `- Meal: dish`; a dish can
  link to a recipe.
- Shopping for the week goes in `Lists/Shopping.md`, not here.

### `recipe`: Meals/Recipes/<Title>.md

Front matter may give `serves`, `time` (minutes) and `tags`. The body has
`## Ingredients` (one per line, as tasks so they can be ticked off while shopping or
cooking) and `## Method`.

### `routine`: Routines/<Title>.md

```markdown
---
type: routine
who: ["[[Sam]]", "[[Mia]]"]
---
# Bedtime

- [ ] Bath
- [ ] Pyjamas
- [ ] Brush teeth
- [ ] Two stories
```

A checklist that comes round again. FamLogic clears the ticks when the routine starts
again, so don't keep history in it.

### `rhythm`: Rhythms/<Title>.md

```markdown
---
type: rhythm
who: ["#kids"]
days: [mon, tue, wed, thu, fri]
---
# School days

- 07:00 [[Morning]]
- 15:30 [[After school]]
- 19:30 [[Bedtime]]
```

Which routine is current, and from when, on the given `days`. In `who`, `#name` means
a group or tag.

## What FamLogic does with the vault today

The vault is new. FamLogic currently creates the folders, the home note, this guide and
the **People** notes. The other types above are the agreed format: the apps'
lists, to-dos, chores, calendar, meals and routines are moving onto them in the coming
versions. Notes you write in this format now will be picked up as each one arrives.
Until then they're ordinary Markdown that people can read in Obsidian or any editor.

Chat messages and locations are *not* in the vault (there are too many of them, and
they're private). Agents reach those through FamLogic's API, with a key a parent
creates.
