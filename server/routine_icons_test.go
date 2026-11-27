package main

import "testing"

func TestRoutineEmoji(t *testing.T) {
	for text, want := range map[string]string{"Brush teeth": "🪥", "Get dressed": "👕", "Pack school bag": "🎒", "🦖 Feed Rexy": "🦖", "Something else": ""} {
		if got, _ := routineEmoji(text); got != want {
			t.Errorf("%q: got %q, want %q", text, got, want)
		}
	}
	if _, rest := routineEmoji("🦖 Feed Rexy"); rest != "Feed Rexy" {
		t.Errorf("rest: %q", rest)
	}
}
