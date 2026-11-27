package main

// Pictures for routine items, so young children can follow a routine without
// reading: "Brush teeth" shows a toothbrush, "Pyjamas" a nightshirt. Each item gets
// one automatically from its words; starting the item with an emoji ("🦖 Feed
// Rexy") sets it by hand. Emoji work everywhere (the app, the TV, the console),
// so they are the pictures; a client may draw its own icon for the key instead.

import (
	"strings"
	"unicode"
	"unicode/utf8"
)

// routineIcon is one picture in the library: a key, its emoji, and the words that pick it.
type routineIcon struct {
	Key   string
	Emoji string
	Words []string
}

// routineIcons is checked in order, so longer, more specific words come first.
var routineIcons = []routineIcon{
	{"teeth", "🪥", []string{"brush teeth", "teeth", "toothbrush", "floss"}},
	{"hair", "🪮", []string{"brush hair", "comb", "hair"}},
	{"face", "🧼", []string{"wash face", "face", "wash hands", "hands", "soap"}},
	{"shower", "🚿", []string{"shower"}},
	{"bath", "🛁", []string{"bath"}},
	{"toilet", "🚽", []string{"toilet", "potty", "wee", "bathroom"}},
	{"pyjamas", "🌙", []string{"pyjamas", "pajamas", "pjs", "nightie"}},
	{"dressed", "👕", []string{"get dressed", "dressed", "clothes", "uniform"}},
	{"shoes", "👟", []string{"shoes", "trainers", "boots", "socks"}},
	{"coat", "🧥", []string{"coat", "jacket", "hat", "gloves"}},
	{"bed", "🛏️", []string{"make bed", "make the bed", "bed"}},
	{"breakfast", "🥣", []string{"breakfast", "cereal"}},
	{"lunch", "🥪", []string{"lunch", "lunchbox"}},
	{"dinner", "🍽️", []string{"dinner", "supper", "tea time", "table"}},
	{"snack", "🍎", []string{"snack", "fruit"}},
	{"water", "💧", []string{"water", "drink"}},
	{"medicine", "💊", []string{"medicine", "vitamins", "tablets", "inhaler"}},
	{"bag", "🎒", []string{"school bag", "backpack", "bag"}},
	{"homework", "✏️", []string{"homework", "spelling", "worksheet"}},
	{"reading", "📖", []string{"story", "read", "reading", "book"}},
	{"music", "🎵", []string{"piano", "practice", "music", "violin", "guitar"}},
	{"tidy", "🧸", []string{"tidy", "toys", "clean up", "put away"}},
	{"laundry", "🧺", []string{"laundry", "washing basket", "dirty clothes"}},
	{"dishes", "🍴", []string{"dishes", "dishwasher", "plates"}},
	{"bins", "🗑️", []string{"bins", "rubbish", "trash", "recycling"}},
	{"dog", "🐕", []string{"dog", "walk the dog", "puppy"}},
	{"cat", "🐈", []string{"cat", "kitten"}},
	{"pet", "🐾", []string{"feed the", "pet", "fish", "hamster", "rabbit"}},
	{"plants", "🪴", []string{"plants", "water the", "garden"}},
	{"screens", "📺", []string{"tv", "screen", "tablet", "ipad", "games"}},
	{"exercise", "🏃", []string{"exercise", "run", "stretch", "sport", "swim"}},
	{"sunscreen", "🧴", []string{"sunscreen", "suncream", "lotion"}},
	{"hug", "🤗", []string{"hug", "kiss", "cuddle", "goodnight"}},
	{"sleep", "😴", []string{"sleep", "lights out", "nap"}},
	{"pray", "🙏", []string{"pray", "prayers", "grace"}},
	{"car", "🚗", []string{"car", "school run", "leave"}},
	{"chores", "🧹", []string{"chores", "sweep", "hoover", "vacuum"}},
}

// leadingEmoji splits an item that starts with an emoji ("🦖 Feed Rexy") into the
// emoji and the rest. ok is false if it doesn't start with one.
func leadingEmoji(text string) (emoji, rest string, ok bool) {
	t := strings.TrimSpace(text)
	i := 0
	for i < len(t) {
		r, n := utf8.DecodeRuneInString(t[i:])
		if isEmojiRune(r) && !unicode.IsLetter(r) && !unicode.IsDigit(r) {
			i += n
			continue
		}
		break
	}
	if i == 0 {
		return "", text, false
	}
	return t[:i], strings.TrimSpace(t[i:]), true
}

// routineEmoji gives an item's picture: its own leading emoji, else one from its
// words, else "". text is returned without a leading emoji.
func routineEmoji(text string) (emoji, rest string) {
	if e, r, ok := leadingEmoji(text); ok {
		return e, r
	}
	low := " " + strings.ToLower(text) + " "
	for _, ic := range routineIcons {
		for _, w := range ic.Words {
			if strings.Contains(low, " "+w+" ") || strings.Contains(low, " "+w) && len(w) > 4 {
				return ic.Emoji, text
			}
		}
	}
	return "", text
}
