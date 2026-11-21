package main

import (
	"strings"
	"unicode"
	"unicode/utf8"
)

// Emoji: a person can show an emoji on their map pin instead of their initial.

// cleanEmoji checks an emoji picked in the app or dashboard. "" clears it.
// It accepts one emoji, including ones built from several code points (skin
// tones, flags, families), but not plain text.
func cleanEmoji(e string) (string, bool) {
	e = strings.TrimSpace(e)
	if e == "" {
		return "", true
	}
	if len(e) > 40 || utf8.RuneCountInString(e) > 10 || !utf8.ValidString(e) {
		return "", false
	}
	pictographic := false
	for _, r := range e {
		switch {
		case unicode.IsSpace(r) || unicode.IsControl(r):
			return "", false
		case r < 0x80 && (unicode.IsLetter(r) || unicode.IsPunct(r)):
			return "", false // plain text such as "AB" or "?!"
		case r >= 0x2000:
			pictographic = true
		}
	}
	return e, pictographic
}
