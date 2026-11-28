package main

// Driveway: the family's vehicles. Each one is a note in the vault's Vehicles
// folder, with its fuel log and service history as Markdown tables, so it reads well
// in Obsidian and an agent can add a fill-up. Service jobs that come round again
// (an oil change every 6 months) become chores in Upkeep, for the vehicle.

import (
	"fmt"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"time"
)

type FuelEntry struct {
	Date     string  `json:"date"`     // YYYY-MM-DD
	Odometer float64 `json:"odometer"` // miles or km
	Amount   float64 `json:"amount"`   // gallons or litres
	Cost     float64 `json:"cost"`
	Full     bool    `json:"full"` // filled to the top (economy is worked out between full fills)
	Economy  float64 `json:"economy,omitempty"`
}

type ServiceEntry struct {
	Date     string  `json:"date"`
	Odometer float64 `json:"odometer,omitempty"`
	What     string  `json:"what"`
	Cost     float64 `json:"cost,omitempty"`
}

type Vehicle struct {
	ID      string         `json:"id"`
	Name    string         `json:"name"`
	Make    string         `json:"make"`
	Model   string         `json:"model"`
	Year    string         `json:"year"`
	Plate   string         `json:"plate"`
	Fuel    []FuelEntry    `json:"fuel"`
	Service []ServiceEntry `json:"service"`
	// Worked out: the latest odometer, average economy (mpg or L/100 km per the
	// family's units, from full fills), cost per mile/km, and the last 30 days' fuel spend.
	Odometer    float64 `json:"odometer"`
	AvgEconomy  float64 `json:"avgEconomy"`
	CostPerDist float64 `json:"costPerDist"`
	Spend30     float64 `json:"spend30"`
	Reminders   []Chore `json:"reminders"`
	path        string
}

const fuelSection, serviceSection = "Fuel", "Service"

func num(s string) float64 {
	v, _ := strconv.ParseFloat(strings.TrimSpace(strings.TrimLeft(strings.ReplaceAll(s, ",", ""), "$£€")), 64)
	return v
}

// tableRows reads a Markdown table's rows (skipping the header and divider).
func tableRows(text string) [][]string {
	var out [][]string
	for i, l := range strings.Split(text, "\n") {
		l = strings.TrimSpace(l)
		if !strings.HasPrefix(l, "|") || i == 0 || strings.Contains(l, "---") {
			continue
		}
		cells := strings.Split(strings.Trim(l, "|"), "|")
		for j := range cells {
			cells[j] = strings.TrimSpace(cells[j])
		}
		out = append(out, cells)
	}
	return out
}

func (s *Server) vehicles(units string) []Vehicle {
	dir := filepath.Join(s.vault.dir, "Vehicles")
	entries, _ := os.ReadDir(dir)
	var reminders []Chore
	if s.chores != nil {
		all, _ := s.chores.list(s.store.Members(), false)
		for _, c := range all {
			if strings.HasPrefix(c.Source, "vehicle/") {
				reminders = append(reminders, c)
			}
		}
	}
	out := []Vehicle{}
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".md") || strings.HasPrefix(e.Name(), ".") {
			continue
		}
		path := filepath.Join(dir, e.Name())
		n, err := readNote(path)
		if err != nil {
			continue
		}
		front := "---\n" + strings.Join(n.front, "\n") + "\n---\n"
		id := frontValue(front, "famlogic_id")
		if !cardID.MatchString(id) {
			id = strings.ToLower(randomString(10))
			n.front = append(n.front, "famlogic_id: "+id)
			writeAtomic(path, []byte("---\n"+strings.Join(n.front, "\n")+"\n---\n"+n.body))
		}
		v := Vehicle{ID: id, Name: strings.TrimSuffix(e.Name(), ".md"), Make: frontValue(front, "make"), Model: frontValue(front, "model"),
			Year: frontValue(front, "year"), Plate: frontValue(front, "plate"), Fuel: []FuelEntry{}, Service: []ServiceEntry{}, Reminders: []Chore{}, path: path}
		secs := readSections(n.body, []string{fuelSection, serviceSection})
		for _, r := range tableRows("x\n" + secs[fuelSection]) {
			if len(r) >= 4 && dateRe.MatchString(r[0]) {
				v.Fuel = append(v.Fuel, FuelEntry{Date: r[0], Odometer: num(r[1]), Amount: num(r[2]), Cost: num(r[3]), Full: len(r) < 5 || !strings.EqualFold(r[4], "no")})
			}
		}
		for _, r := range tableRows("x\n" + secs[serviceSection]) {
			if len(r) >= 3 && dateRe.MatchString(r[0]) {
				se := ServiceEntry{Date: r[0], Odometer: num(r[1]), What: r[2]}
				if len(r) >= 4 {
					se.Cost = num(r[3])
				}
				v.Service = append(v.Service, se)
			}
		}
		for _, c := range reminders {
			if strings.HasPrefix(c.Source, "vehicle/"+id+":") {
				v.Reminders = append(v.Reminders, c)
			}
		}
		v.work(units)
		out = append(out, v)
	}
	slices.SortFunc(out, func(a, b Vehicle) int { return strings.Compare(a.Name, b.Name) })
	return out
}

// work fills in the economy of each full fill, and the vehicle's totals.
func (v *Vehicle) work(units string) {
	slices.SortFunc(v.Fuel, func(a, b FuelEntry) int {
		return strings.Compare(a.Date+fmt.Sprintf("%012.1f", a.Odometer), b.Date+fmt.Sprintf("%012.1f", b.Odometer))
	})
	slices.SortFunc(v.Service, func(a, b ServiceEntry) int { return strings.Compare(b.Date, a.Date) })
	lastFull, since := -1.0, 0.0
	var dist, amt float64
	for i := range v.Fuel {
		f := &v.Fuel[i]
		v.Odometer = math.Max(v.Odometer, f.Odometer)
		since += f.Amount
		if f.Full {
			if lastFull >= 0 && f.Odometer > lastFull && since > 0 {
				d := f.Odometer - lastFull
				f.Economy = economy(d, since, units)
				dist, amt = dist+d, amt+since
			}
			lastFull, since = f.Odometer, 0
		}
	}
	for _, s := range v.Service {
		v.Odometer = math.Max(v.Odometer, s.Odometer)
	}
	if amt > 0 {
		v.AvgEconomy = economy(dist, amt, units)
	}
	var cost float64
	cutoff := time.Now().AddDate(0, 0, -30).Format(dayLayout)
	for _, f := range v.Fuel {
		cost += f.Cost
		if f.Date >= cutoff {
			v.Spend30 += f.Cost
		}
	}
	if len(v.Fuel) > 1 && v.Fuel[len(v.Fuel)-1].Odometer > v.Fuel[0].Odometer {
		v.CostPerDist = math.Round(100*(cost-v.Fuel[0].Cost)/(v.Fuel[len(v.Fuel)-1].Odometer-v.Fuel[0].Odometer)) / 100
	}
	v.Spend30 = math.Round(v.Spend30*100) / 100
}

// economy: miles per gallon, or litres per 100 km.
func economy(dist, amount float64, units string) float64 {
	if units == "km" {
		return math.Round(1000*amount/dist) / 10
	}
	return math.Round(10*dist/amount) / 10
}

func (s *Server) writeVehicle(v Vehicle) error {
	text := "# " + v.Name + "\n"
	front := []string{"type: vehicle", "famlogic_id: " + v.ID}
	if v.path != "" {
		if n, err := readNote(v.path); err == nil {
			text = n.body
			front = nil
			for _, l := range n.front {
				k, _, _ := strings.Cut(l, ":")
				if !slices.Contains([]string{"make", "model", "year", "plate"}, k) {
					front = append(front, l)
				}
			}
		}
	}
	for _, kv := range [][2]string{{"make", v.Make}, {"model", v.Model}, {"year", v.Year}, {"plate", v.Plate}} {
		if kv[1] != "" {
			front = append(front, kv[0]+": "+strings.ReplaceAll(kv[1], "\n", " "))
		}
	}
	fuel := "| Date | Odometer | Amount | Cost | Full |\n|---|---|---|---|---|\n"
	for _, f := range v.Fuel {
		full := "yes"
		if !f.Full {
			full = "no"
		}
		fuel += fmt.Sprintf("| %s | %g | %g | %.2f | %s |\n", f.Date, f.Odometer, f.Amount, f.Cost, full)
	}
	svc := "| Date | Odometer | What | Cost |\n|---|---|---|---|\n"
	for _, e := range v.Service {
		svc += fmt.Sprintf("| %s | %g | %s | %.2f |\n", e.Date, e.Odometer, strings.ReplaceAll(e.What, "|", "/"), e.Cost)
	}
	body := writeSections(text, map[string]string{fuelSection: fuel, serviceSection: svc}, []string{fuelSection, serviceSection})
	path := filepath.Join(s.vault.dir, "Vehicles", v.Name+".md")
	if err := writeAtomic(path, []byte("---\n"+strings.Join(front, "\n")+"\n---\n"+body)); err != nil {
		return err
	}
	if v.path != "" && v.path != path {
		journalBefore(v.path)
		os.Remove(v.path)
	}
	return nil
}

func (s *Server) drivewaySetup(w http.ResponseWriter, me *Member) bool {
	if s.vault == nil {
		writeError(w, http.StatusServiceUnavailable, "Driveway needs the notes vault, which isn't available on this server")
		return false
	}
	if !s.store.Modules().Enabled["garage"] || me.level("garage") == LevelOff || me.Display {
		writeError(w, http.StatusForbidden, "Driveway isn't switched on for you")
		return false
	}
	return true
}

func canEditVehicles(me *Member) bool {
	return me.role() != RoleDependent && me.access() != AccessYoungerChild && me.level("garage") != LevelView
}

func (s *Server) drivewayUnits() string {
	s.store.mu.RLock()
	defer s.store.mu.RUnlock()
	return s.store.d.DrivewayUnits
}

func (s *Server) findVehicle(id string) (Vehicle, bool) {
	for _, v := range s.vehicles(s.drivewayUnits()) {
		if v.ID == id {
			return v, true
		}
	}
	return Vehicle{}, false
}

// GET /api/vehicles?units=mi|km: every vehicle, worked out in the family's units (or
// the ones asked for, when the family hasn't chosen).
func (s *Server) handleVehicles(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.drivewaySetup(w, me) {
		return
	}
	units := s.drivewayUnits()
	chosen := units != ""
	if !chosen {
		units = r.URL.Query().Get("units")
	}
	if units != "km" {
		units = "mi"
	}
	writeJSON(w, http.StatusOK, map[string]any{"vehicles": s.vehicles(units), "units": units, "unitsChosen": chosen, "canEdit": canEditVehicles(me)})
}

// PUT /api/vehicles/units {units: mi|km|""}: the family's units (a parent).
func (s *Server) handleVehicleUnits(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.drivewaySetup(w, me) {
		return
	}
	if me.role() != RoleParent {
		writeError(w, http.StatusForbidden, "a parent sets the units")
		return
	}
	var body struct {
		Units string `json:"units"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	if body.Units != "mi" && body.Units != "km" {
		body.Units = ""
	}
	s.store.mu.Lock()
	s.store.d.DrivewayUnits = body.Units
	err := s.store.saveLocked()
	s.store.mu.Unlock()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

type vehicleBody struct {
	Name  *string `json:"name"`
	Make  *string `json:"make"`
	Model *string `json:"model"`
	Year  *string `json:"year"`
	Plate *string `json:"plate"`
}

func (b vehicleBody) apply(v *Vehicle) {
	set := func(dst *string, src *string, n int) {
		if src != nil {
			t := strings.TrimSpace(*src)
			if len([]rune(t)) > n {
				t = string([]rune(t)[:n])
			}
			*dst = t
		}
	}
	set(&v.Make, b.Make, 40)
	set(&v.Model, b.Model, 40)
	set(&v.Year, b.Year, 4)
	set(&v.Plate, b.Plate, 16)
}

// POST /api/vehicles {name, make, model, year, plate}
func (s *Server) handleAddVehicle(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.drivewaySetup(w, me) {
		return
	}
	if !canEditVehicles(me) {
		writeError(w, http.StatusForbidden, "ask a parent to add it")
		return
	}
	var body vehicleBody
	if !readJSON(w, r, &body) {
		return
	}
	name := ""
	if body.Name != nil {
		name = cleanCardName(*body.Name)
	}
	if name == "" {
		writeError(w, http.StatusBadRequest, "give it a name, like The Sienna or Dad's truck")
		return
	}
	if _, err := os.Stat(filepath.Join(s.vault.dir, "Vehicles", name+".md")); err == nil {
		writeError(w, http.StatusConflict, "there's already one called "+name)
		return
	}
	v := Vehicle{ID: strings.ToLower(randomString(10)), Name: name}
	body.apply(&v)
	if err := s.writeVehicle(v); err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save it")
		return
	}
	out, _ := s.findVehicle(v.ID)
	writeJSON(w, http.StatusOK, out)
}

// PUT /api/vehicles/{id} (details), DELETE /api/vehicles/{id} (and its reminders retire).
func (s *Server) handleVehicle(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.drivewaySetup(w, me) {
		return
	}
	if !canEditVehicles(me) {
		writeError(w, http.StatusForbidden, "ask a parent to change it")
		return
	}
	v, ok := s.findVehicle(r.PathValue("id"))
	if !ok {
		writeError(w, http.StatusNotFound, "no such vehicle")
		return
	}
	if r.Method == http.MethodDelete {
		for _, c := range v.Reminders {
			s.chores.archive(c.ID)
		}
		journalBefore(v.path)
		if err := os.Remove(v.path); err != nil {
			writeError(w, http.StatusInternalServerError, "couldn't remove it")
			return
		}
		w.WriteHeader(http.StatusNoContent)
		return
	}
	var body vehicleBody
	if !readJSON(w, r, &body) {
		return
	}
	body.apply(&v)
	if body.Name != nil {
		if name := cleanCardName(*body.Name); name != "" {
			v.Name = name
		}
	}
	if err := s.writeVehicle(v); err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save it")
		return
	}
	out, _ := s.findVehicle(v.ID)
	writeJSON(w, http.StatusOK, out)
}

// POST /api/vehicles/{id}/fuel {date?, odometer, amount, cost, full?}: a fill-up.
func (s *Server) handleAddFuel(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.drivewaySetup(w, me) {
		return
	}
	if !canEditVehicles(me) {
		writeError(w, http.StatusForbidden, "ask a parent to log it")
		return
	}
	v, ok := s.findVehicle(r.PathValue("id"))
	if !ok {
		writeError(w, http.StatusNotFound, "no such vehicle")
		return
	}
	var body struct {
		Date     string  `json:"date"`
		Odometer float64 `json:"odometer"`
		Amount   float64 `json:"amount"`
		Cost     float64 `json:"cost"`
		Full     *bool   `json:"full"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	if body.Date == "" {
		body.Date = today()
	}
	if !dateRe.MatchString(body.Date) || body.Amount <= 0 || body.Amount > 500 || body.Cost < 0 || body.Odometer < 0 {
		writeError(w, http.StatusBadRequest, "a fill-up needs how much fuel (and the odometer and cost, if you have them)")
		return
	}
	full := body.Full == nil || *body.Full
	v.Fuel = append(v.Fuel, FuelEntry{Date: body.Date, Odometer: body.Odometer, Amount: body.Amount, Cost: body.Cost, Full: full})
	if err := s.writeVehicle(v); err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save it")
		return
	}
	out, _ := s.findVehicle(v.ID)
	writeJSON(w, http.StatusOK, out)
}

// POST /api/vehicles/{id}/service {date?, odometer?, what, cost?, every?}: a job done
// (an oil change, new tyres). With every ("6 months"), it's also a reminder in Upkeep.
func (s *Server) handleAddService(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.drivewaySetup(w, me) {
		return
	}
	if !canEditVehicles(me) {
		writeError(w, http.StatusForbidden, "ask a parent to log it")
		return
	}
	v, ok := s.findVehicle(r.PathValue("id"))
	if !ok {
		writeError(w, http.StatusNotFound, "no such vehicle")
		return
	}
	var body struct {
		Date     string  `json:"date"`
		Odometer float64 `json:"odometer"`
		What     string  `json:"what"`
		Cost     float64 `json:"cost"`
		Every    string  `json:"every"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	if body.Date == "" {
		body.Date = today()
	}
	body.What = strings.TrimSpace(strings.ReplaceAll(body.What, "\n", " "))
	if !dateRe.MatchString(body.Date) || body.What == "" || len([]rune(body.What)) > 80 || !validEvery(body.Every) {
		writeError(w, http.StatusBadRequest, "say what was done (and how often it comes round, if it does)")
		return
	}
	v.Service = append(v.Service, ServiceEntry{Date: body.Date, Odometer: body.Odometer, What: body.What, Cost: body.Cost})
	if err := s.writeVehicle(v); err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save it")
		return
	}
	// A job that comes round again: a reminder chore for the vehicle (one per job).
	if body.Every != "" && s.chores != nil {
		src := "vehicle/" + v.ID + ":" + homeSlug(body.What)
		due := nextDue(body.Every, body.Date)
		members := s.store.Members()
		existing := slices.IndexFunc(v.Reminders, func(c Chore) bool { return c.Source == src })
		if existing >= 0 {
			every := body.Every
			s.chores.update(v.Reminders[existing].ID, ChoreInput{Every: &every, Due: &due}, members)
		} else {
			title, every, forName := body.What+" ("+v.Name+")", body.Every, v.Name
			s.chores.create(ChoreInput{Title: &title, Every: &every, Due: &due, Source: &src, For: &forName, Who: &[]string{}}, members)
		}
	}
	out, _ := s.findVehicle(v.ID)
	writeJSON(w, http.StatusOK, out)
}

// DELETE /api/vehicles/{id}/{fuel|service}/{i}: take back an entry (by its place in the list).
func (s *Server) handleDeleteVehicleEntry(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.drivewaySetup(w, me) {
		return
	}
	if !canEditVehicles(me) {
		writeError(w, http.StatusForbidden, "ask a parent")
		return
	}
	v, ok := s.findVehicle(r.PathValue("id"))
	i, err := strconv.Atoi(r.PathValue("i"))
	if !ok || err != nil {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	switch r.PathValue("kind") {
	case "fuel":
		if i < 0 || i >= len(v.Fuel) {
			writeError(w, http.StatusNotFound, "not found")
			return
		}
		v.Fuel = slices.Delete(v.Fuel, i, i+1)
	case "service":
		if i < 0 || i >= len(v.Service) {
			writeError(w, http.StatusNotFound, "not found")
			return
		}
		v.Service = slices.Delete(v.Service, i, i+1)
	default:
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	if err := s.writeVehicle(v); err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save it")
		return
	}
	out, _ := s.findVehicle(v.ID)
	writeJSON(w, http.StatusOK, out)
}
