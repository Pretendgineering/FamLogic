package main

// Place is a named area on the map (e.g. Home, School) that belongs to one circle.
// See geofences in places.go (filled in with the places feature).
type Place struct {
	ID       string  `json:"id"`
	CircleID string  `json:"circleId"`
	Name     string  `json:"name"`
	Lat      float64 `json:"lat"`
	Lon      float64 `json:"lon"`
	Radius   float64 `json:"radius"` // metres
}
