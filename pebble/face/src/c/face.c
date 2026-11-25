// FamLogic Face, the watchface: the time, and a grid of up to 8 people below it.
// Each person is their initial in their colour, ringed by what they're doing
// (amber driving, green moving, purple still, grey not heard from lately), next to
// one word for where they are ("Home", "Driving", "Out", "3h"). Long fence names
// scroll like an LED sign for a few seconds after a flick of the wrist. During
// someone's SOS the whole face turns red with their name and last known place.
//
// The phone app sends: K_TEXT "S,ff8800,t,Home;M,3355ff,d,Driving;" (initial,
// colour, state, label), K_FLAGS (16 = light theme), K_NUM (the colour theme, as in
// the app: 0 Default, 1 Warm paper, 2 Forest, 3 Ocean, 4 High contrast), and for an SOS K_NAME and
// K_STATUS. Keep in step with pebble/PebbleBridge.kt.
#include <pebble.h>

enum { K_CMD = 0, K_NAME = 4, K_STATUS = 5, K_FLAGS = 7, K_TEXT = 8, K_KIND = 9, K_NUM = 10 };
enum { C_FACE = 5, C_HELLO = 20 };
enum { M_LIGHT = 16 };
enum { P_LINE = 1, P_FLAGS = 2, P_SOS_NAME = 3, P_SOS_WHERE = 4, P_THEME = 5 };

#define MAX_PEOPLE 8
#define MARQUEE_FRAMES 150 // ~10 s at 15 fps
#define MARQUEE_PAUSE 12   // frames to show the start before scrolling
#define MARQUEE_GAP 24     // pixels between the end of a name and its next pass

typedef struct {
  char initial[5];
  char label[21];
  uint32_t color;
  char state; // s sos, d driving, m moving, t still, a away
} Person;

static Window *s_win;
static Layer *s_clock, *s_grid, *s_labels[MAX_PEOPLE], *s_sos;
static Person s_people[MAX_PEOPLE];
static int s_count;
static bool s_light;
static int s_theme; // the app's colour theme
static char s_time_buf[8], s_date_buf[24];
static char s_sos_name[25], s_sos_where[64];
static AppTimer *s_marquee;
static int s_frame = -1; // -1 = not scrolling

// ---------------------------------------------------------------- look

// The app's themes, in the watch's colours: background, text and accent, dark then light.
// Default is a muted Dracula (deep navy and lavender), like the app and the console.
#if defined(PBL_COLOR)
static const uint8_t THEMES[5][2][3] = {
  {{GColorOxfordBlueARGB8, GColorWhiteARGB8, GColorLavenderIndigoARGB8}, {GColorWhiteARGB8, GColorOxfordBlueARGB8, GColorIndigoARGB8}},
  {{GColorBlackARGB8, GColorPastelYellowARGB8, GColorMelonARGB8}, {GColorPastelYellowARGB8, GColorBlackARGB8, GColorDarkCandyAppleRedARGB8}},
  {{GColorDarkGreenARGB8, GColorWhiteARGB8, GColorMintGreenARGB8}, {GColorWhiteARGB8, GColorDarkGreenARGB8, GColorIslamicGreenARGB8}},
  {{GColorOxfordBlueARGB8, GColorWhiteARGB8, GColorPictonBlueARGB8}, {GColorCelesteARGB8, GColorOxfordBlueARGB8, GColorCobaltBlueARGB8}},
  {{GColorBlackARGB8, GColorWhiteARGB8, GColorYellowARGB8}, {GColorWhiteARGB8, GColorBlackARGB8, GColorBlueARGB8}},
};
static GColor theme_color(int i) {
  int t = (s_theme >= 0 && s_theme < 5) ? s_theme : 0;
  return (GColor){.argb = THEMES[t][s_light ? 1 : 0][i]};
}
static GColor bg(void) { return theme_color(0); }
static GColor fg(void) { return theme_color(1); }
static GColor accent(void) { return theme_color(2); }
#else
static GColor bg(void) { return s_light ? GColorWhite : GColorBlack; }
static GColor fg(void) { return s_light ? GColorBlack : GColorWhite; }
static GColor accent(void) { return fg(); }
#endif

#if defined(PBL_COLOR)
static GColor state_color(char state) {
  switch (state) {
    case 's': return GColorRed;
    case 'd': return GColorChromeYellow;
    case 'm': return s_light ? GColorIslamicGreen : GColorGreen;
    case 'a': return s_light ? GColorLightGray : GColorDarkGray;
    default: return accent();
  }
}
#endif

// Layout, worked out once from the screen size.
static int s_rows_max, s_row_h, s_dot_r, s_grid_top;
static GFont s_time_font, s_date_font, s_label_font;

// Serif type, like the app's headings (Liberation Serif, OFL; see resources/fonts).
static bool s_fonts_loaded;
static void load_fonts(bool big_time, bool big_text) {
  if (s_fonts_loaded) {
    fonts_unload_custom_font(s_time_font);
    fonts_unload_custom_font(s_date_font);
    fonts_unload_custom_font(s_label_font);
  }
  s_time_font = fonts_load_custom_font(resource_get_handle(big_time ? RESOURCE_ID_FONT_SERIF_TIME_48 : RESOURCE_ID_FONT_SERIF_TIME_38));
  s_date_font = fonts_load_custom_font(resource_get_handle(big_text ? RESOURCE_ID_FONT_SERIF_DATE_22 : RESOURCE_ID_FONT_SERIF_DATE_17));
  s_label_font = fonts_load_custom_font(resource_get_handle(big_text ? RESOURCE_ID_FONT_SERIF_LABEL_20 : RESOURCE_ID_FONT_SERIF_LABEL_15));
  s_fonts_loaded = true;
}

static void layout(GRect b) {
#if defined(PBL_ROUND)
  bool big = b.size.w >= 240;
  load_fonts(big, big);
  s_grid_top = big ? 112 : 82;
  s_row_h = big ? 30 : 24;
  s_rows_max = big ? 4 : 3;
#else
  bool big = b.size.w >= 200;
  load_fonts(big, big);
  s_grid_top = big ? 90 : 68;
  s_row_h = (b.size.h - s_grid_top - 2) / 4;
  s_rows_max = 4;
#endif
  s_dot_r = s_row_h / 2 - (s_row_h > 28 ? 3 : 4);
}

static int shown(void) { return s_count < s_rows_max * 2 ? s_count : s_rows_max * 2; }

// The rectangle for person i: two columns; an odd last person is centred.
static GRect cell(GRect screen, int i) {
  int n = shown();
  int rows = (n + 1) / 2;
  // Fewer rows sit a little lower, nearer the middle.
  int top = s_grid_top + (rows < s_rows_max ? (s_rows_max - rows) * s_row_h / 4 : 0);
  int row = i / 2, col = i % 2;
  int y = top + row * s_row_h;
  int width = screen.size.w - PBL_IF_ROUND_ELSE(16, 12); // breathing room from the edges
#if defined(PBL_ROUND)
  // The usable width of a row is the circle's chord at its far edge.
  int cy = screen.size.h / 2, rad = screen.size.w / 2 - 8;
  int dy = (y + s_row_h > cy) ? (y + s_row_h - cy) : (cy - y);
  int chord2 = rad * rad - dy * dy;
  int chord = 0;
  if (chord2 > 0) { int c = 0; while ((c + 1) * (c + 1) <= chord2) c++; chord = 2 * c; }
  if (chord < width) width = chord;
#endif
  int left = (screen.size.w - width) / 2;
  bool alone = (i == n - 1) && (n % 2 == 1);
  int w = width / 2;
  int x = alone ? (screen.size.w - w) / 2 : left + col * w;
  return GRect(x, y, w, s_row_h);
}

// ---------------------------------------------------------------- drawing

static void draw_clock(Layer *layer, GContext *g) {
  GRect b = layer_get_bounds(layer);
  bool sos = s_sos_name[0] != 0;
  graphics_context_set_text_color(g, sos ? GColorWhite : fg());
  int time_h = PBL_IF_ROUND_ELSE(b.size.w >= 240 ? 46 : 40, b.size.w >= 200 ? 46 : 40);
  int top = PBL_IF_ROUND_ELSE(b.size.w >= 240 ? 26 : 12, 2);
  graphics_draw_text(g, s_time_buf, s_time_font, GRect(0, top, b.size.w, time_h), GTextOverflowModeFill, GTextAlignmentCenter, NULL);
  if (sos) return;
  graphics_context_set_text_color(g, accent());
  // A few pixels between the time and the date, so they don't crowd each other.
  graphics_draw_text(g, s_date_buf, s_date_font, GRect(0, top + time_h + 3, b.size.w, 28), GTextOverflowModeFill, GTextAlignmentCenter, NULL);
}

static void draw_grid(Layer *layer, GContext *g) {
  GRect screen = layer_get_bounds(layer);
  int n = shown();
  if (n == 0) {
    graphics_context_set_text_color(g, PBL_IF_COLOR_ELSE(GColorDarkGray, fg()));
    graphics_draw_text(g, "Open FamLogic on your phone", fonts_get_system_font(FONT_KEY_GOTHIC_14),
                       GRect(16, s_grid_top + 10, screen.size.w - 32, 40), GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
    return;
  }
  for (int i = 0; i < n; i++) {
    Person *p = &s_people[i];
    GRect c = cell(screen, i);
    GPoint ctr = GPoint(c.origin.x + s_dot_r + 2, c.origin.y + c.size.h / 2);
#if defined(PBL_COLOR)
    // Their colour, greyed out when not heard from lately; ringed by what they're doing.
    graphics_context_set_fill_color(g, p->state == 'a' ? (s_light ? GColorLightGray : GColorDarkGray) : GColorFromHEX(p->color));
    graphics_fill_circle(g, ctr, s_dot_r - 2);
    graphics_context_set_stroke_color(g, state_color(p->state));
    graphics_context_set_stroke_width(g, 3);
    graphics_draw_circle(g, ctr, s_dot_r);
    graphics_context_set_text_color(g, GColorWhite);
#else
    // Black and white: a filled dot while still, ringed while moving, double ring driving, outline when away.
    graphics_context_set_fill_color(g, fg());
    graphics_context_set_stroke_color(g, fg());
    graphics_context_set_stroke_width(g, 1);
    if (p->state == 'a') graphics_draw_circle(g, ctr, s_dot_r - 2);
    else graphics_fill_circle(g, ctr, s_dot_r - 2);
    if (p->state == 'm' || p->state == 'd' || p->state == 's') graphics_draw_circle(g, ctr, s_dot_r);
    if (p->state == 'd' || p->state == 's') graphics_draw_circle(g, ctr, s_dot_r + 2);
    graphics_context_set_text_color(g, p->state == 'a' ? fg() : bg());
#endif
    int r = s_dot_r - 2;
    GFont f = fonts_get_system_font(r >= 12 ? FONT_KEY_GOTHIC_18_BOLD : FONT_KEY_GOTHIC_14_BOLD);
    graphics_draw_text(g, p->initial, f, GRect(ctr.x - r, ctr.y - r + (r >= 12 ? -2 : -1), 2 * r, 2 * r),
                       GTextOverflowModeFill, GTextAlignmentCenter, NULL);
  }
}

// Each label is its own layer, so a scrolling name is cut off at both ends of its slot.
static void draw_label(Layer *layer, GContext *g) {
  int i = *(int *)layer_get_data(layer);
  if (i >= shown()) return;
  Person *p = &s_people[i];
  GRect b = layer_get_bounds(layer);
  graphics_context_set_text_color(g, p->state == 'a' ? PBL_IF_COLOR_ELSE(GColorDarkGray, fg()) : fg());
  GSize size = graphics_text_layout_get_content_size(p->label, s_label_font, GRect(0, 0, 400, b.size.h), GTextOverflowModeFill, GTextAlignmentLeft);
  int y = (b.size.h - size.h) / 2 - 3;
  if (size.w <= b.size.w || s_frame < 0) {
    graphics_draw_text(g, p->label, s_label_font, GRect(0, y, b.size.w, b.size.h), GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
    return;
  }
  int period = size.w + MARQUEE_GAP;
  int off = s_frame > MARQUEE_PAUSE ? ((s_frame - MARQUEE_PAUSE) * 2) % period : 0;
  graphics_draw_text(g, p->label, s_label_font, GRect(-off, y, size.w + 4, b.size.h), GTextOverflowModeFill, GTextAlignmentLeft, NULL);
  graphics_draw_text(g, p->label, s_label_font, GRect(-off + period, y, size.w + 4, b.size.h), GTextOverflowModeFill, GTextAlignmentLeft, NULL);
}

static void draw_sos(Layer *layer, GContext *g) {
  if (!s_sos_name[0]) return;
  GRect b = layer_get_bounds(layer);
  graphics_context_set_fill_color(g, PBL_IF_COLOR_ELSE(GColorRed, GColorBlack));
  graphics_fill_rect(g, b, 0, GCornerNone);
  int inset = PBL_IF_ROUND_ELSE(b.size.w / 8, 6);
  int w = b.size.w - 2 * inset;
  // Below the clock, which stays on top.
  int y = PBL_IF_ROUND_ELSE(b.size.w >= 240 ? 84 : 62, b.size.w >= 200 ? 66 : 50);
  graphics_context_set_text_color(g, GColorWhite);
  graphics_draw_text(g, "SOS", fonts_get_system_font(FONT_KEY_BITHAM_42_BOLD), GRect(inset, y - 12, w, 48), GTextOverflowModeFill, GTextAlignmentCenter, NULL);
  graphics_draw_text(g, s_sos_name, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD), GRect(inset, y + 34, w, 32), GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
  graphics_draw_text(g, s_sos_where, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD), GRect(inset, y + 66, w, 44), GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
}

// ---------------------------------------------------------------- marquee

static void marquee_step(void *data) {
  s_marquee = NULL;
  if (s_frame < 0) return;
  s_frame++;
  if (s_frame > MARQUEE_FRAMES) s_frame = -1; // back to resting ("Grandma's…")
  for (int i = 0; i < MAX_PEOPLE; i++) layer_mark_dirty(s_labels[i]);
  if (s_frame >= 0) s_marquee = app_timer_register(66, marquee_step, NULL);
}

static bool any_long_label(void) {
  if (!s_labels[0]) return false;
  int w = layer_get_bounds(s_labels[0]).size.w;
  for (int i = 0; i < shown(); i++) {
    GSize size = graphics_text_layout_get_content_size(s_people[i].label, s_label_font, GRect(0, 0, 400, 40), GTextOverflowModeFill, GTextAlignmentLeft);
    if (size.w > w) return true;
  }
  return false;
}

static void marquee_start(void) {
  if (s_sos_name[0] || !any_long_label()) return;
  s_frame = 0;
  if (!s_marquee) s_marquee = app_timer_register(66, marquee_step, NULL);
}

static void wrist_flick(AccelAxisType axis, int32_t direction) { marquee_start(); }

// ---------------------------------------------------------------- data

static void place_labels(void) {
  GRect screen = layer_get_bounds(window_get_root_layer(s_win));
  for (int i = 0; i < MAX_PEOPLE; i++) {
    if (i < shown()) {
      GRect c = cell(screen, i);
      int x = c.origin.x + 2 * s_dot_r + 6;
      layer_set_frame(s_labels[i], GRect(x, c.origin.y, c.origin.x + c.size.w - x - 1, c.size.h));
      layer_set_hidden(s_labels[i], s_sos_name[0] != 0);
    } else {
      layer_set_hidden(s_labels[i], true);
    }
  }
}

static void apply(void) {
  window_set_background_color(s_win, bg());
  layer_set_hidden(s_grid, s_sos_name[0] != 0);
  layer_set_hidden(s_sos, s_sos_name[0] == 0);
  place_labels();
  layer_mark_dirty(window_get_root_layer(s_win));
}

static void parse(const char *line) {
  s_count = 0;
  const char *p = line;
  while (*p && s_count < MAX_PEOPLE) {
    Person *d = &s_people[s_count];
    memset(d, 0, sizeof(*d));
    int i = 0;
    while (*p && *p != ',' && i < 4) d->initial[i++] = *p++;
    while (*p && *p != ',') p++;
    if (*p == ',') p++;
    char hex[7] = {0};
    for (i = 0; i < 6 && *p && *p != ','; i++) hex[i] = *p++;
    d->color = (uint32_t)strtol(hex, NULL, 16);
    if (*p == ',') p++;
    d->state = (*p && *p != ',' && *p != ';') ? *p++ : 't';
    while (*p && *p != ',' && *p != ';') p++;
    if (*p == ',') p++;
    for (i = 0; i < 20 && *p && *p != ';'; i++) d->label[i] = *p++;
    while (*p && *p != ';') p++;
    if (*p == ';') p++;
    s_count++;
  }
}

static void copy_str(char *dst, size_t n, DictionaryIterator *it, uint32_t key) {
  Tuple *t = dict_find(it, key);
  if (t && t->type == TUPLE_CSTRING) {
    strncpy(dst, t->value->cstring, n - 1);
    dst[n - 1] = 0;
  }
}

static void inbox(DictionaryIterator *it, void *ctx) {
  Tuple *cmd = dict_find(it, K_CMD);
  if (!cmd || cmd->value->int32 != C_FACE) return;
  Tuple *text = dict_find(it, K_TEXT);
  if (text && text->type == TUPLE_CSTRING) {
    parse(text->value->cstring);
    persist_write_string(P_LINE, text->value->cstring); // so the grid is there straight away next time
  }
  Tuple *flags = dict_find(it, K_FLAGS);
  if (flags) {
    s_light = (flags->value->int32 & M_LIGHT) != 0;
    persist_write_int(P_FLAGS, flags->value->int32);
  }
  Tuple *theme = dict_find(it, K_NUM);
  if (theme) {
    s_theme = theme->value->int32;
    persist_write_int(P_THEME, s_theme);
  }
  bool was_sos = s_sos_name[0] != 0;
  s_sos_name[0] = s_sos_where[0] = 0;
  copy_str(s_sos_name, sizeof(s_sos_name), it, K_NAME);
  copy_str(s_sos_where, sizeof(s_sos_where), it, K_STATUS);
  persist_write_string(P_SOS_NAME, s_sos_name);
  persist_write_string(P_SOS_WHERE, s_sos_where);
  if (s_sos_name[0] && !was_sos) light_enable_interaction();
  apply();
  marquee_start(); // show new names in full once
}

// ---------------------------------------------------------------- time

static void update_time(void) {
  time_t now = time(NULL);
  struct tm *t = localtime(&now);
  strftime(s_time_buf, sizeof(s_time_buf), clock_is_24h_style() ? "%H:%M" : "%I:%M", t);
  if (!clock_is_24h_style() && s_time_buf[0] == '0') memmove(s_time_buf, s_time_buf + 1, strlen(s_time_buf));
  strftime(s_date_buf, sizeof(s_date_buf), "%a %d %b", t);
  layer_mark_dirty(s_clock);
}

static void hello(void) {
  DictionaryIterator *out;
  if (app_message_outbox_begin(&out) != APP_MSG_OK) return;
  dict_write_int32(out, K_CMD, C_HELLO);
  dict_write_cstring(out, K_KIND, "face");
  dict_write_int32(out, K_NUM, battery_state_service_peek().charge_percent);
  app_message_outbox_send();
}

static void tick(struct tm *t, TimeUnits changed) {
  update_time();
  if (t->tm_min % 10 == 0) hello(); // a refresh now and then, in case a change was missed
}

// ---------------------------------------------------------------- start

static void load(Window *w) {
  Layer *root = window_get_root_layer(w);
  GRect b = layer_get_bounds(root);
  layout(b);
  s_grid = layer_create(b);
  layer_set_update_proc(s_grid, draw_grid);
  layer_add_child(root, s_grid);
  for (int i = 0; i < MAX_PEOPLE; i++) {
    s_labels[i] = layer_create_with_data(GRect(0, 0, 10, 10), sizeof(int));
    *(int *)layer_get_data(s_labels[i]) = i;
    layer_set_update_proc(s_labels[i], draw_label);
    layer_add_child(root, s_labels[i]);
  }
  s_sos = layer_create(b);
  layer_set_update_proc(s_sos, draw_sos);
  layer_add_child(root, s_sos);
  s_clock = layer_create(b); // on top, so the time shows over the red SOS screen too
  layer_set_update_proc(s_clock, draw_clock);
  layer_add_child(root, s_clock);

  char saved[300];
  if (persist_exists(P_LINE)) { persist_read_string(P_LINE, saved, sizeof(saved)); parse(saved); }
  if (persist_exists(P_FLAGS)) s_light = (persist_read_int(P_FLAGS) & M_LIGHT) != 0;
  if (persist_exists(P_THEME)) s_theme = persist_read_int(P_THEME);
  if (persist_exists(P_SOS_NAME)) persist_read_string(P_SOS_NAME, s_sos_name, sizeof(s_sos_name));
  if (persist_exists(P_SOS_WHERE)) persist_read_string(P_SOS_WHERE, s_sos_where, sizeof(s_sos_where));
  update_time();
  apply();
}

static void unload(Window *w) {
  if (s_marquee) { app_timer_cancel(s_marquee); s_marquee = NULL; }
  layer_destroy(s_clock);
  layer_destroy(s_sos);
  for (int i = 0; i < MAX_PEOPLE; i++) layer_destroy(s_labels[i]);
  layer_destroy(s_grid);
  if (s_fonts_loaded) {
    fonts_unload_custom_font(s_time_font);
    fonts_unload_custom_font(s_date_font);
    fonts_unload_custom_font(s_label_font);
    s_fonts_loaded = false;
  }
}

int main(void) {
  s_win = window_create();
  window_set_window_handlers(s_win, (WindowHandlers){.load = load, .unload = unload});
  window_stack_push(s_win, true);
  app_message_register_inbox_received(inbox);
  app_message_open(512, 64);
  tick_timer_service_subscribe(MINUTE_UNIT, tick);
  accel_tap_service_subscribe(wrist_flick);
  hello();
  app_event_loop();
  accel_tap_service_unsubscribe();
  tick_timer_service_unsubscribe();
  window_destroy(s_win);
}
