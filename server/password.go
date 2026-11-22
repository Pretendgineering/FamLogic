package main

import (
	"bufio"
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	_ "embed"
	"encoding/base64"
	"errors"
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"
)

// ---- password rules ----
//
// Following current guidance (NIST SP 800-63B): length and a blocklist of
// common/guessable passwords, no "must contain a symbol" rules (they push people
// to predictable patterns like Password1! and get in the way of password managers),
// and long passwords and passphrases are welcome.

const (
	minPasswordLen = 12
	maxPasswordLen = 128
)

//go:embed commonpasswords.txt
var commonPasswordsTxt string

var commonPasswords = func() map[string]bool {
	m := map[string]bool{}
	sc := bufio.NewScanner(strings.NewReader(commonPasswordsTxt))
	for sc.Scan() {
		if w := strings.TrimSpace(sc.Text()); w != "" && !strings.HasPrefix(w, "#") {
			m[w] = true
		}
	}
	return m
}()

// Look-alike characters people swap in for letters (p@ssw0rd).
var unLeet = strings.NewReplacer("@", "a", "4", "a", "0", "o", "1", "i", "!", "i", "3", "e", "$", "s", "5", "s", "7", "t", "+", "t")

// Digits and symbols people pad a word with (password1234!).
var padding = regexp.MustCompile(`^[^\p{L}]+|[^\p{L}]+$`)

// isCommon reports whether a password is a common one, possibly dressed up with
// capitals, look-alike characters or digits/symbols around it.
func isCommon(pw string) bool {
	lower := strings.ToLower(pw)
	core := padding.ReplaceAllString(lower, "") // strip padding first, so "2024!" isn't read as letters
	for _, v := range []string{lower, core, unLeet.Replace(core), padding.ReplaceAllString(unLeet.Replace(lower), "")} {
		if commonPasswords[v] {
			return true
		}
	}
	return false
}

// CheckPassword returns a friendly explanation if a password isn't acceptable.
// username and name are the person's own, which shouldn't appear in it.
func CheckPassword(pw, username, name string) error {
	n := utf8.RuneCountInString(pw)
	if n < minPasswordLen {
		return fmt.Errorf("use at least %d characters (a few words together works well)", minPasswordLen)
	}
	if n > maxPasswordLen {
		return fmt.Errorf("use at most %d characters", maxPasswordLen)
	}
	distinct := map[rune]bool{}
	allDigits := true
	for _, r := range pw {
		distinct[r] = true
		if !unicode.IsDigit(r) {
			allDigits = false
		}
	}
	if allDigits {
		return errors.New("don't use only numbers")
	}
	if len(distinct) < 5 {
		return errors.New("this password is too repetitive")
	}
	if isCommon(pw) {
		return errors.New("this password is too common and easy to guess")
	}
	lower := strings.ToLower(pw)
	for _, word := range []string{username, name, "famlogic"} {
		word = strings.ToLower(strings.TrimSpace(word))
		if utf8.RuneCountInString(word) >= 3 && strings.Contains(lower, word) {
			return errors.New("don't include your username, your name or the app's name in your password")
		}
	}
	return nil
}

// ---- usernames ----

var usernameRe = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{2,31}$`)

// NormalizeUsername lower-cases and checks a username: 3-32 characters of
// letters, digits, dots, dashes and underscores. Usernames are case-insensitive.
func NormalizeUsername(u string) (string, error) {
	u = strings.ToLower(strings.TrimSpace(u))
	if !usernameRe.MatchString(u) {
		return "", errors.New("usernames are 3-32 characters: letters, numbers, dots, dashes or underscores, starting with a letter or number")
	}
	return u, nil
}

// ---- hashing ----
//
// PBKDF2-SHA256 with 600,000 iterations (OWASP's recommendation), from Go's
// standard library. Stored as "pbkdf2-sha256$<iterations>$<salt>$<hash>".

var pbkdf2Iterations = 600_000 // lowered in tests

func HashPassword(pw string) (string, error) {
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	key, err := pbkdf2.Key(sha256.New, pw, salt, pbkdf2Iterations, 32)
	if err != nil {
		return "", err
	}
	enc := base64.RawStdEncoding
	return fmt.Sprintf("pbkdf2-sha256$%d$%s$%s", pbkdf2Iterations, enc.EncodeToString(salt), enc.EncodeToString(key)), nil
}

// dummyHash is checked against when a username doesn't exist, so a wrong
// username takes as long as a wrong password and can't be told apart.
var dummyHash = func() string {
	h, _ := HashPassword(randomString(16))
	return h
}()

func VerifyPassword(pw, stored string) bool {
	if stored == "" {
		stored = dummyHash
	}
	parts := strings.Split(stored, "$")
	if len(parts) != 4 || parts[0] != "pbkdf2-sha256" {
		return false
	}
	iter, err := strconv.Atoi(parts[1])
	if err != nil || iter < 1 {
		return false
	}
	enc := base64.RawStdEncoding
	salt, err1 := enc.DecodeString(parts[2])
	want, err2 := enc.DecodeString(parts[3])
	if err1 != nil || err2 != nil {
		return false
	}
	got, err := pbkdf2.Key(sha256.New, pw, salt, iter, len(want))
	return err == nil && subtle.ConstantTimeCompare(got, want) == 1
}
