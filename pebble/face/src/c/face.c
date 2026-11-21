// Thereabouts Family watchface: the time, and a row of your family underneath.
// Each person is their initial in their colour: filled when they're at one of
// your fences, an outline when they're out, faded when away or paused, a red ring
// for SOS and a double ring while driving. The phone app sends the row
// ("S,ff8800,h;M,3355ff,d;") whenever something changes.
#include <pebble.h>

enum { K_CMD = 0, K_TEXT = 8, K_NUM = 10, K_KIND = 9 };
enum { C_FACE = 5, C_HELLO = 20 };

#define MAX_PEOPLE 8
typedef struct {
  char initial[5];
  uint32_t color;
  char state; // s sos, d driving, h at a fence, o out, a away
} Dot;

static Window *s_win;
static TextLayer *s_time, *s_date;
static Layer *s_row;
static Dot s_dots[MAX_PEOPLE];
static int s_count;
static char s_time_buf[8], s_date_buf[24];

static void parse(const char *line) {
  s_count = 0;
  const char *p = line;
  while (*p && s_count < MAX_PEOPLE) {
    Dot *d = &s_dots[s_count];
    memset(d, 0, sizeof(*d));
    int i = 0;
    while (*p && *p != ',' && i < 4) d->initial[i++] = *p++;
    while (*p && *p != ',') p++;
    if (*p == ',') p++;
    char hex[7] = {0};
    for (i = 0; i < 6 && *p && *p != ','; i++) hex[i] = *p++;
    d->color = (uint32_t)strtol(hex, NULL, 16);
    if (*p == ',') p++;
    d->state = *p ? *p : 'o';
    while (*p && *p != ';') p++;
    if (*p == ';') p++;
    s_count++;
  }
  persist_write_string(1, line); // so the row is there straight away next time
  layer_mark_dirty(s_row);
}

static void draw_row(Layer *layer, GContext *g) {
  GRect b = layer_get_bounds(layer);
  if (s_count == 0) return;
#if PBL_DISPLAY_WIDTH >= 200
  int r = s_count > 6 ? 12 : 15; // the bigger Time 2 and Round 2 screens
#else
  int r = s_count > 6 ? 9 : 11;
#endif
  int gap = 4;
  int total = s_count * (2 * r) + (s_count - 1) * gap;
  int x = (b.size.w - total) / 2 + r;
  int y = b.size.h / 2;
  GFont font = fonts_get_system_font(r >= 15 ? FONT_KEY_GOTHIC_18_BOLD : r > 9 ? FONT_KEY_GOTHIC_14_BOLD : FONT_KEY_GOTHIC_09);
  for (int i = 0; i < s_count; i++, x += 2 * r + gap) {
    Dot *d = &s_dots[i];
    GPoint c = GPoint(x, y);
#if defined(PBL_COLOR)
    GColor col = GColorFromHEX(d->color);
    bool filled = d->state == 'h' || d->state == 's' || d->state == 'd';
    if (d->state == 'a') col = GColorDarkGray;
    graphics_context_set_fill_color(g, col);
    graphics_context_set_stroke_color(g, col);
    graphics_context_set_stroke_width(g, 2);
    if (filled || d->state == 'a') graphics_fill_circle(g, c, r);
    else graphics_draw_circle(g, c, r - 1);
    graphics_context_set_text_color(g, (filled || d->state == 'a') ? GColorWhite : col);
#else
    bool filled = d->state == 'h' || d->state == 's';
    graphics_context_set_fill_color(g, GColorWhite);
    graphics_context_set_stroke_color(g, GColorWhite);
    graphics_context_set_stroke_width(g, 1);
    if (filled) graphics_fill_circle(g, c, r);
    else graphics_draw_circle(g, c, r - 1);
    graphics_context_set_text_color(g, filled ? GColorBlack : GColorWhite);
#endif
    if (d->state == 's') {
      graphics_context_set_stroke_color(g, PBL_IF_COLOR_ELSE(GColorRed, GColorWhite));
      graphics_context_set_stroke_width(g, 3);
      graphics_draw_circle(g, c, r + 3);
    } else if (d->state == 'd') {
      graphics_context_set_stroke_color(g, GColorWhite);
      graphics_context_set_stroke_width(g, 1);
      graphics_draw_circle(g, c, r + 3);
    }
    graphics_draw_text(g, d->initial, font, GRect(c.x - r, c.y - r + (r >= 15 ? 2 : r > 9 ? 1 : 3), 2 * r, 2 * r),
                       GTextOverflowModeFill, GTextAlignmentCenter, NULL);
  }
}

static void update_time(void) {
  time_t now = time(NULL);
  struct tm *t = localtime(&now);
  strftime(s_time_buf, sizeof(s_time_buf), clock_is_24h_style() ? "%H:%M" : "%I:%M", t);
  if (!clock_is_24h_style() && s_time_buf[0] == '0') memmove(s_time_buf, s_time_buf + 1, strlen(s_time_buf));
  strftime(s_date_buf, sizeof(s_date_buf), "%a %d %b", t);
  text_layer_set_text(s_time, s_time_buf);
  text_layer_set_text(s_date, s_date_buf);
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
  if (t->tm_min % 15 == 0) hello(); // a refresh now and then, in case a change was missed
}

static void inbox(DictionaryIterator *it, void *ctx) {
  Tuple *cmd = dict_find(it, K_CMD);
  Tuple *text = dict_find(it, K_TEXT);
  if (cmd && cmd->value->int32 == C_FACE && text) parse(text->value->cstring);
}

static void load(Window *w) {
  Layer *root = window_get_root_layer(w);
  GRect b = layer_get_bounds(root);
  int mid = b.size.h / 2;
#if PBL_DISPLAY_WIDTH >= 200
  s_time = text_layer_create(GRect(0, mid - 66, b.size.w, 60));
  s_date = text_layer_create(GRect(0, mid - 4, b.size.w, 30));
#else
  s_time = text_layer_create(GRect(0, mid - 52, b.size.w, 50));
  s_date = text_layer_create(GRect(0, mid - 4, b.size.w, 24));
#endif
  TextLayer *ls[] = {s_time, s_date};
  for (int i = 0; i < 2; i++) {
    text_layer_set_background_color(ls[i], GColorClear);
    text_layer_set_text_color(ls[i], GColorWhite);
    text_layer_set_text_alignment(ls[i], GTextAlignmentCenter);
    layer_add_child(root, text_layer_get_layer(ls[i]));
  }
#if PBL_DISPLAY_WIDTH >= 200
  text_layer_set_font(s_time, fonts_get_system_font(FONT_KEY_ROBOTO_BOLD_SUBSET_49));
  text_layer_set_font(s_date, fonts_get_system_font(FONT_KEY_GOTHIC_24));
#else
  text_layer_set_font(s_time, fonts_get_system_font(FONT_KEY_BITHAM_42_BOLD));
  text_layer_set_font(s_date, fonts_get_system_font(FONT_KEY_GOTHIC_18));
#endif
#if defined(PBL_COLOR)
  text_layer_set_text_color(s_date, GColorLavenderIndigo);
#endif
  s_row = layer_create(GRect(0, mid + PBL_IF_ROUND_ELSE(32, 26), b.size.w, 44));
  layer_set_update_proc(s_row, draw_row);
  layer_add_child(root, s_row);
  update_time();
  if (persist_exists(1)) {
    char saved[160];
    persist_read_string(1, saved, sizeof(saved));
    parse(saved);
  }
}

static void unload(Window *w) {
  text_layer_destroy(s_time);
  text_layer_destroy(s_date);
  layer_destroy(s_row);
}

int main(void) {
  s_win = window_create();
  window_set_background_color(s_win, PBL_IF_COLOR_ELSE(GColorFromHEX(0x1d1e28), GColorBlack));
  window_set_window_handlers(s_win, (WindowHandlers){.load = load, .unload = unload});
  window_stack_push(s_win, true);
  app_message_register_inbox_received(inbox);
  app_message_open(256, 64);
  tick_timer_service_subscribe(MINUTE_UNIT, tick);
  hello();
  app_event_loop();
  tick_timer_service_unsubscribe();
  window_destroy(s_win);
}
