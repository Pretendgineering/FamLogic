// FamLogic for Pebble: see everyone at a glance, check in, send or answer an
// SOS, ask for a pick-up, nudge someone. Above the top of the list: a radar of
// everyone around you, then a small street map of each person. Holding SELECT
// anywhere starts an SOS. The phone app (PebbleKit 2) does all the talking to the
// server; message keys and commands match PebbleBridge.kt (keep them in step).
#include <pebble.h>

// ---- protocol (keep in step with PebbleBridge.kt) ----
enum {
  K_CMD = 0, K_IDX, K_COUNT, K_ID, K_NAME, K_STATUS, K_COLOR, K_FLAGS, K_TEXT, K_KIND, K_NUM, K_INITIAL,
  K_STATE, K_BEARING, K_DIST, K_LABEL, K_DATA, K_W, K_H, K_STRIDE, K_CHUNK, K_RADIUS
};
enum { C_PERSON = 1, C_ME, C_ALERT, C_RESULT, C_FACE, C_FENCE, C_MAP, C_MAP_FAIL, C_CHORE };
enum { C_HELLO = 20, C_CHECKIN, C_SOS, C_SOS_END, C_REQUEST, C_SEEN, C_SHARING, C_BATTERY, C_MAP_REQ, C_CHORE_DONE };
enum { F_ME = 1, F_SOS = 2, F_DRIVING = 4, F_AWAY = 8, F_PLACE = 16, F_ADULT = 32 }; // F_ADULT: you and they are both grown-ups
enum { M_SHARING = 1, M_SOS = 2, M_HEART_RATE = 4, M_FALLS = 8, M_LIGHT = 16, M_QUICK_SOS = 32, M_MILES = 64 };

// Persistent storage keys (1 and 2 are shared with the fall-detection worker).
#define P_FALL_AT 1
#define P_FALLS_ON 2
#define P_ME_FLAGS 3

#define MAX_PEOPLE 12
typedef struct {
  char id[16];
  char name[25];
  char initial[5];
  char status[41];
  char detail[72];
  char label[25];
  uint32_t color;
  uint8_t flags;
  char state; // s sos, d driving, m moving, t still, a away
  int16_t bearing;
  int32_t dist; // metres from you, -1 unknown
#if defined(PBL_COLOR)
  bool has_icon;  // their emoji, drawn by the phone (ICON_PX square, 8-bit colour with alpha)
  uint8_t icon[22 * 22];
#endif
} Person;
#define ICON_PX 22

#define MAX_FENCES 8
typedef struct {
  char name[25];
  int16_t bearing;
  int32_t dist, radius;
} Fence;

static Person s_people[MAX_PEOPLE];
static int s_count;          // people received so far (me included, first)
static bool s_loaded;        // a full list has arrived
static Fence s_fences[MAX_FENCES];

// Your chores due now (Upkeep), from the phone.
#define MAX_CHORES 8
typedef struct {
  char id[16];
  char title[32];
  int stars;
} Chore;
static Chore s_chores[MAX_CHORES];
static int s_chore_count;
static int s_fence_count;
static int s_me_flags;
static char s_me_status[64] = "Connecting to your phone…";
static char s_me_name[25];

static Window *s_main;
static MenuLayer *s_menu;

// ---------------------------------------------------------------- look

static bool light(void) { return (s_me_flags & M_LIGHT) != 0; }
static GColor bg(void) { return light() ? GColorWhite : GColorBlack; }
static GColor fg(void) { return light() ? GColorBlack : GColorWhite; }
// The purple of the phone app and dashboard.
static GColor accent(void) { return PBL_IF_COLOR_ELSE(light() ? GColorIndigo : GColorLavenderIndigo, fg()); }
static GColor highlight(void) { return PBL_IF_COLOR_ELSE(GColorIndigo, fg()); }
static GColor muted(void) { return PBL_IF_COLOR_ELSE(light() ? GColorDarkGray : GColorLightGray, fg()); }

#if defined(PBL_COLOR)
// Ring colours, the same as the watchface: amber driving, green moving, purple still, grey away.
static GColor state_color(char state) {
  switch (state) {
    case 's': return GColorRed;
    case 'd': return GColorChromeYellow;
    case 'm': return light() ? GColorIslamicGreen : GColorGreen;
    case 'a': return light() ? GColorLightGray : GColorDarkGray;
    default: return light() ? GColorIndigo : GColorLavenderIndigo;
  }
}
#endif

static void fmt_distance(char *buf, size_t n, int32_t m) {
  if (s_me_flags & M_MILES) {
    int tenths = (int)((m * 10 + 804) / 1609);
    if (m < 160) snprintf(buf, n, "%d ft", (int)(m * 328 / 100 / 10 * 10));
    else if (tenths < 100) snprintf(buf, n, "%d.%d mi", tenths / 10, tenths % 10);
    else snprintf(buf, n, "%d mi", tenths / 10);
  } else {
    if (m < 1000) snprintf(buf, n, "%d m", (int)(m / 10 * 10));
    else if (m < 10000) snprintf(buf, n, "%d.%d km", (int)(m / 1000), (int)(m % 1000 / 100));
    else snprintf(buf, n, "%d km", (int)(m / 1000));
  }
}

// ---------------------------------------------------------------- sending

static void send_cmd(int cmd, const char *id, const char *kind, const char *text, int num) {
  DictionaryIterator *out;
  if (app_message_outbox_begin(&out) != APP_MSG_OK) return;
  dict_write_int32(out, K_CMD, cmd);
  if (id) dict_write_cstring(out, K_ID, id);
  if (kind) dict_write_cstring(out, K_KIND, kind);
  if (text) dict_write_cstring(out, K_TEXT, text);
  if (num) dict_write_int32(out, K_NUM, num);
  app_message_outbox_send();
}

static int heart_rate(void) {
#if defined(PBL_HEALTH)
  if (s_me_flags & M_HEART_RATE) {
    HealthServiceAccessibilityMask m = health_service_metric_accessible(HealthMetricHeartRateBPM, time(NULL), time(NULL));
    if (m & HealthServiceAccessibilityMaskAvailable) return (int)health_service_peek_current_value(HealthMetricHeartRateBPM);
  }
#endif
  return 0;
}

// ---------------------------------------------------------------- toast

static Window *s_toast;
static TextLayer *s_toast_text;
static char s_toast_buf[96];
static AppTimer *s_toast_timer;

static void toast_hide(void *data) {
  s_toast_timer = NULL;
  if (s_toast && window_stack_contains_window(s_toast)) window_stack_remove(s_toast, true);
}

static void toast_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  GRect b = layer_get_bounds(root);
  s_toast_text = text_layer_create(GRect(PBL_IF_ROUND_ELSE(20, 8), b.size.h / 2 - 40, b.size.w - PBL_IF_ROUND_ELSE(40, 16), 80));
  text_layer_set_text_alignment(s_toast_text, GTextAlignmentCenter);
  text_layer_set_font(s_toast_text, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD));
  text_layer_set_background_color(s_toast_text, GColorClear);
  text_layer_set_text_color(s_toast_text, PBL_IF_COLOR_ELSE(GColorWhite, fg()));
  text_layer_set_overflow_mode(s_toast_text, GTextOverflowModeWordWrap);
  layer_add_child(root, text_layer_get_layer(s_toast_text));
}

static void toast_unload(Window *w) { text_layer_destroy(s_toast_text); }

static void toast(const char *text) {
  strncpy(s_toast_buf, text, sizeof(s_toast_buf) - 1);
  if (!s_toast) {
    s_toast = window_create();
    window_set_window_handlers(s_toast, (WindowHandlers){.load = toast_load, .unload = toast_unload});
  }
  window_set_background_color(s_toast, PBL_IF_COLOR_ELSE(GColorIndigo, bg()));
  if (!window_stack_contains_window(s_toast)) window_stack_push(s_toast, true);
  text_layer_set_text(s_toast_text, s_toast_buf);
  if (s_toast_timer) app_timer_cancel(s_toast_timer);
  s_toast_timer = app_timer_register(2500, toast_hide, NULL);
}

// ---------------------------------------------------------------- SOS by holding SELECT
// Hold SELECT anywhere (in the list, the radar, the maps or the SOS screen): a
// panel counts 3, 2, 1 with a gentle buzz each second and a bar filling up; at
// zero it sends, buzzes twice and says "Sent". Let go before zero to cancel.
// While your SOS is on, the same hold (2 seconds) says you're OK.

static Layer *s_hold;            // the panel; moved onto whichever window is showing
static AppTimer *s_hold_timer;
static int s_hold_total_ms, s_hold_elapsed_ms;
static int64_t s_hold_started; // ms

static int64_t now_ms(void) {
  time_t sec;
  uint16_t ms;
  time_ms(&sec, &ms);
  return (int64_t)sec * 1000 + ms;
}
static bool s_hold_ending;       // this hold ends an SOS
static enum { HOLD_IDLE, HOLD_COUNTING, HOLD_DONE, HOLD_CANCELLED } s_hold_state;

static const uint32_t GENTLE[] = {45};
static const uint32_t DOUBLE[] = {60, 90, 60};

static void buzz(const uint32_t *d, int n) {
  VibePattern p = {.durations = d, .num_segments = n};
  vibes_enqueue_custom_pattern(p);
}

static void hold_hide(void *data) {
  s_hold_timer = NULL;
  s_hold_state = HOLD_IDLE;
  layer_set_hidden(s_hold, true);
}

static void hold_tick(void *data) {
  s_hold_timer = NULL;
  if (s_hold_state != HOLD_COUNTING) return;
  // Real time, not timer ticks (which can run late), so 3 seconds is 3 seconds.
  int before = s_hold_elapsed_ms / 1000;
  s_hold_elapsed_ms = (int)(now_ms() - s_hold_started);
  if (s_hold_elapsed_ms >= s_hold_total_ms) {
    // Zero: send.
    if (s_hold_ending) {
      send_cmd(C_SOS_END, NULL, NULL, NULL, 0);
      s_me_flags &= ~M_SOS;
    } else {
      send_cmd(C_SOS, NULL, "watch", NULL, heart_rate());
      s_me_flags |= M_SOS;
    }
    buzz(DOUBLE, ARRAY_LENGTH(DOUBLE));
    s_hold_state = HOLD_DONE;
    layer_mark_dirty(s_hold);
    if (s_menu) menu_layer_reload_data(s_menu);
    s_hold_timer = app_timer_register(2500, hold_hide, NULL);
    return;
  }
  if (s_hold_elapsed_ms / 1000 != before) buzz(GENTLE, 1); // 2, 1
  layer_mark_dirty(s_hold);
  s_hold_timer = app_timer_register(50, hold_tick, NULL);
}

static void hold_down(ClickRecognizerRef r, void *ctx) {
  if (s_hold_timer) { app_timer_cancel(s_hold_timer); s_hold_timer = NULL; }
  s_hold_ending = (s_me_flags & M_SOS) != 0;
  s_hold_total_ms = s_hold_ending ? 2000 : 3000;
  s_hold_elapsed_ms = 0;
  s_hold_started = now_ms();
  s_hold_state = HOLD_COUNTING;
  light_enable_interaction();
  buzz(GENTLE, 1); // 3
  layer_set_hidden(s_hold, false);
  layer_mark_dirty(s_hold);
  s_hold_timer = app_timer_register(50, hold_tick, NULL);
}

static void hold_up(ClickRecognizerRef r, void *ctx) {
  if (s_hold_state != HOLD_COUNTING) return;
  if (s_hold_timer) { app_timer_cancel(s_hold_timer); s_hold_timer = NULL; }
  s_hold_state = HOLD_CANCELLED;
  layer_mark_dirty(s_hold);
  s_hold_timer = app_timer_register(700, hold_hide, NULL);
}

static void draw_hold(Layer *layer, GContext *g) {
  GRect b = layer_get_bounds(layer);
  bool ending = s_hold_ending;
  graphics_context_set_fill_color(g, PBL_IF_COLOR_ELSE(ending ? GColorIslamicGreen : GColorRed, GColorBlack));
  graphics_fill_rect(g, b, 0, GCornerNone);
  graphics_context_set_text_color(g, GColorWhite);
  int inset = PBL_IF_ROUND_ELSE(b.size.w / 7, 8);
  int w = b.size.w - 2 * inset;
  int mid = b.size.h / 2;
  const char *title = ending ? "I'm OK" : "SOS";
  char big[12] = "";
  const char *line = "";
  if (s_hold_state == HOLD_COUNTING) {
    int left = (s_hold_total_ms - s_hold_elapsed_ms + 999) / 1000;
    snprintf(big, sizeof(big), "%d", left);
    line = "Keep holding · let go to cancel";
  } else if (s_hold_state == HOLD_DONE) {
    strcpy(big, "Sent");
    line = ending ? "Your family knows you're OK" : "Your family has been alerted";
  } else if (s_hold_state == HOLD_CANCELLED) {
    strcpy(big, "–");
    line = "Cancelled";
  }
  graphics_draw_text(g, title, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD), GRect(inset, mid - 76, w, 32), GTextOverflowModeFill, GTextAlignmentCenter, NULL);
  graphics_draw_text(g, big, fonts_get_system_font(FONT_KEY_BITHAM_42_BOLD), GRect(inset, mid - 44, w, 50), GTextOverflowModeFill, GTextAlignmentCenter, NULL);
  graphics_draw_text(g, line, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD), GRect(inset, mid + 8, w, 44), GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
  // The bar fills up as you hold.
  int bar_w = w - 8;
  int filled = s_hold_state == HOLD_COUNTING ? bar_w * s_hold_elapsed_ms / s_hold_total_ms : (s_hold_state == HOLD_DONE ? bar_w : 0);
  GRect bar = GRect(inset + 4, mid + 54, bar_w, 10);
  graphics_context_set_stroke_color(g, GColorWhite);
  graphics_context_set_stroke_width(g, 1);
  graphics_draw_round_rect(g, bar, 4);
  graphics_context_set_fill_color(g, GColorWhite);
  if (filled > 0) graphics_fill_rect(g, GRect(bar.origin.x, bar.origin.y, filled, bar.size.h), 4, GCornersAll);
}

/** Puts the hold panel on top of [w] (call when the window appears). */
static void hold_attach(Window *w) {
  Layer *root = window_get_root_layer(w);
  if (!s_hold) {
    s_hold = layer_create(layer_get_bounds(root));
    layer_set_update_proc(s_hold, draw_hold);
    layer_set_hidden(s_hold, true);
  }
  layer_remove_from_parent(s_hold);
  layer_add_child(root, s_hold);
}

/** Holding SELECT for [delay_ms] starts the countdown. */
static void hold_subscribe(uint16_t delay_ms) {
  window_long_click_subscribe(BUTTON_ID_SELECT, delay_ms, hold_down, hold_up);
}

// ---------------------------------------------------------------- the SOS screen
// Where "SOS" in your menu leads, and where Quick Launch opens if you asked for that.

static Window *s_sos;
static TextLayer *s_sos_title, *s_sos_text;

static void sos_show_state(void) {
  if (!s_sos_title) return;
  text_layer_set_text(s_sos_title, (s_me_flags & M_SOS) ? "SOS is on" : "SOS");
  text_layer_set_font(s_sos_title, fonts_get_system_font((s_me_flags & M_SOS) ? FONT_KEY_GOTHIC_28_BOLD : FONT_KEY_BITHAM_42_BOLD));
  text_layer_set_text(s_sos_text, (s_me_flags & M_SOS) ? "Your family has been alerted.\n\nHold SELECT to say you're OK."
                                                       : "Hold SELECT to alert everyone.\n\nIt counts 3, 2, 1: let go to cancel.");
}

static void sos_clicks(void *ctx) { hold_subscribe(150); }

static void sos_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  GRect b = layer_get_bounds(root);
  int inset = PBL_IF_ROUND_ELSE(24, 8);
  s_sos_title = text_layer_create(GRect(inset, b.size.h / 2 - 70, b.size.w - 2 * inset, 48));
  s_sos_text = text_layer_create(GRect(inset, b.size.h / 2 - 20, b.size.w - 2 * inset, 90));
  TextLayer *ls[] = {s_sos_title, s_sos_text};
  for (int i = 0; i < 2; i++) {
    text_layer_set_background_color(ls[i], GColorClear);
    text_layer_set_text_color(ls[i], GColorWhite);
    text_layer_set_text_alignment(ls[i], GTextAlignmentCenter);
    text_layer_set_overflow_mode(ls[i], GTextOverflowModeWordWrap);
    layer_add_child(root, text_layer_get_layer(ls[i]));
  }
  text_layer_set_font(s_sos_text, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
  sos_show_state();
}

static void sos_appear(Window *w) { hold_attach(w); }

static void sos_unload(Window *w) {
  text_layer_destroy(s_sos_title);
  text_layer_destroy(s_sos_text);
  s_sos_title = s_sos_text = NULL;
}

static void open_sos(void) {
  if (!s_sos) {
    s_sos = window_create();
    window_set_background_color(s_sos, PBL_IF_COLOR_ELSE(GColorDarkCandyAppleRed, GColorBlack));
    window_set_click_config_provider(s_sos, sos_clicks);
    window_set_window_handlers(s_sos, (WindowHandlers){.load = sos_load, .appear = sos_appear, .unload = sos_unload});
  }
  window_stack_push(s_sos, true);
}

// ---------------------------------------------------------------- SOS (someone else's)

static Window *s_alarm;
static TextLayer *s_alarm_title, *s_alarm_text;
static AppTimer *s_alarm_timer;
static char s_alarm_id[16], s_alarm_name[25], s_alarm_buf[160];

static const uint32_t ALARM_SEGMENTS[] = {400, 150, 400, 150, 800};

static void alarm_ring(void *data) {
  VibePattern pat = {.durations = ALARM_SEGMENTS, .num_segments = ARRAY_LENGTH(ALARM_SEGMENTS)};
  vibes_enqueue_custom_pattern(pat);
#if defined(PBL_SPEAKER)
  speaker_play_tone(1760, 400, 80, SpeakerWaveformSquare);
#endif
  s_alarm_timer = app_timer_register(3000, alarm_ring, NULL);
}

static void alarm_stop(void) {
  if (s_alarm_timer) { app_timer_cancel(s_alarm_timer); s_alarm_timer = NULL; }
  vibes_cancel();
#if defined(PBL_SPEAKER)
  speaker_stop();
#endif
}

static void alarm_select(ClickRecognizerRef r, void *ctx) {
  alarm_stop();
  send_cmd(C_SEEN, s_alarm_id, NULL, NULL, 0); // "Mom has seen your SOS"
  window_stack_remove(s_alarm, true);
}

static void alarm_back(ClickRecognizerRef r, void *ctx) {
  alarm_stop();
  window_stack_remove(s_alarm, true);
}

static void alarm_clicks(void *ctx) {
  window_single_click_subscribe(BUTTON_ID_SELECT, alarm_select);
  window_single_click_subscribe(BUTTON_ID_BACK, alarm_back);
}

static void alarm_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  GRect b = layer_get_bounds(root);
  int inset = PBL_IF_ROUND_ELSE(22, 6);
  s_alarm_title = text_layer_create(GRect(inset, PBL_IF_ROUND_ELSE(30, 14), b.size.w - 2 * inset, 50));
  s_alarm_text = text_layer_create(GRect(inset, PBL_IF_ROUND_ELSE(80, 64), b.size.w - 2 * inset, b.size.h - 70));
  TextLayer *ls[] = {s_alarm_title, s_alarm_text};
  for (int i = 0; i < 2; i++) {
    text_layer_set_background_color(ls[i], GColorClear);
    text_layer_set_text_color(ls[i], GColorWhite);
    text_layer_set_text_alignment(ls[i], GTextAlignmentCenter);
    text_layer_set_overflow_mode(ls[i], GTextOverflowModeWordWrap);
    layer_add_child(root, text_layer_get_layer(ls[i]));
  }
  text_layer_set_font(s_alarm_title, fonts_get_system_font(FONT_KEY_BITHAM_42_BOLD));
  text_layer_set_font(s_alarm_text, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD));
  text_layer_set_text(s_alarm_title, "SOS");
}

static void alarm_unload(Window *w) {
  alarm_stop();
  text_layer_destroy(s_alarm_title);
  text_layer_destroy(s_alarm_text);
}

static void open_alarm(const char *id, const char *name, const char *text) {
  strncpy(s_alarm_id, id, sizeof(s_alarm_id) - 1);
  strncpy(s_alarm_name, name, sizeof(s_alarm_name) - 1);
  snprintf(s_alarm_buf, sizeof(s_alarm_buf), "%s %s\n\nSELECT: I've seen it", s_alarm_name, text);
  if (!s_alarm) {
    s_alarm = window_create();
    window_set_background_color(s_alarm, PBL_IF_COLOR_ELSE(GColorRed, GColorBlack));
    window_set_click_config_provider(s_alarm, alarm_clicks);
    window_set_window_handlers(s_alarm, (WindowHandlers){.load = alarm_load, .unload = alarm_unload});
  }
  if (!window_stack_contains_window(s_alarm)) window_stack_push(s_alarm, true);
  text_layer_set_text(s_alarm_text, s_alarm_buf);
  light_enable_interaction();
  if (!s_alarm_timer) alarm_ring(NULL);
}

// ---------------------------------------------------------------- fall detection countdown

#define FALL_SECONDS 30
static Window *s_fall;
static TextLayer *s_fall_text;
static AppTimer *s_fall_timer;
static int s_fall_left;
static char s_fall_buf[96];

static void fall_tick(void *data) {
  s_fall_timer = NULL;
  s_fall_left--;
  if (s_fall_left <= 0) {
    send_cmd(C_SOS, NULL, "fall", NULL, heart_rate());
    s_me_flags |= M_SOS;
    window_stack_remove(s_fall, true);
    toast("SOS sent");
    return;
  }
  if (s_fall_left % 5 == 0 || s_fall_left <= 5) vibes_double_pulse();
  snprintf(s_fall_buf, sizeof(s_fall_buf), "Did you fall?\n\nSending SOS in %d s.\nPress any button to cancel.", s_fall_left);
  text_layer_set_text(s_fall_text, s_fall_buf);
  s_fall_timer = app_timer_register(1000, fall_tick, NULL);
}

static void fall_cancel(ClickRecognizerRef r, void *ctx) {
  if (s_fall_timer) { app_timer_cancel(s_fall_timer); s_fall_timer = NULL; }
  window_stack_remove(s_fall, true);
  toast("Cancelled");
}

static void fall_clicks(void *ctx) {
  window_single_click_subscribe(BUTTON_ID_SELECT, fall_cancel);
  window_single_click_subscribe(BUTTON_ID_UP, fall_cancel);
  window_single_click_subscribe(BUTTON_ID_DOWN, fall_cancel);
  window_single_click_subscribe(BUTTON_ID_BACK, fall_cancel);
}

static void fall_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  GRect b = layer_get_bounds(root);
  int inset = PBL_IF_ROUND_ELSE(22, 6);
  s_fall_text = text_layer_create(GRect(inset, b.size.h / 2 - 60, b.size.w - 2 * inset, 120));
  text_layer_set_background_color(s_fall_text, GColorClear);
  text_layer_set_text_color(s_fall_text, GColorWhite);
  text_layer_set_text_alignment(s_fall_text, GTextAlignmentCenter);
  text_layer_set_font(s_fall_text, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD));
  layer_add_child(root, text_layer_get_layer(s_fall_text));
}

static void fall_unload(Window *w) {
  if (s_fall_timer) { app_timer_cancel(s_fall_timer); s_fall_timer = NULL; }
  text_layer_destroy(s_fall_text);
}

static void open_fall_countdown(void) {
  if (!s_fall) {
    s_fall = window_create();
    window_set_background_color(s_fall, PBL_IF_COLOR_ELSE(GColorOrange, GColorBlack));
    window_set_click_config_provider(s_fall, fall_clicks);
    window_set_window_handlers(s_fall, (WindowHandlers){.load = fall_load, .unload = fall_unload});
  }
  window_stack_push(s_fall, true);
  light_enable_interaction();
  vibes_long_pulse();
  s_fall_left = FALL_SECONDS + 1;
  fall_tick(NULL);
}

// ---------------------------------------------------------------- menus (check in, requests, nudges)

static ActionMenuConfig menu_config(ActionMenuLevel *root);

static void action_menu_closed(ActionMenu *menu, const ActionMenuItem *performed, void *ctx) {
  action_menu_hierarchy_destroy(action_menu_get_root_level(menu), NULL, NULL);
}

static ActionMenuConfig menu_config(ActionMenuLevel *root) {
  return (ActionMenuConfig){
      .root_level = root,
      .colors = {.background = PBL_IF_COLOR_ELSE(GColorIndigo, GColorBlack), .foreground = GColorWhite},
      .align = ActionMenuAlignCenter,
      .did_close = action_menu_closed,
  };
}

#if defined(PBL_MICROPHONE)
static DictationSession *s_dictation;

static void dictation_done(DictationSession *session, DictationSessionStatus status, char *transcription, void *ctx) {
  if (status == DictationSessionStatusSuccess && transcription) {
    send_cmd(C_CHECKIN, NULL, NULL, transcription, 0);
  }
}
#endif

static void checkin_preset(ActionMenu *menu, const ActionMenuItem *item, void *ctx) {
  const char *text = (const char *)action_menu_item_get_action_data(item);
#if defined(PBL_MICROPHONE)
  if (text == NULL) {
    if (!s_dictation) s_dictation = dictation_session_create(160, dictation_done, NULL);
    if (s_dictation) dictation_session_start(s_dictation);
    return;
  }
#endif
  send_cmd(C_CHECKIN, NULL, NULL, text, 0);
}

// ---- My jobs: your chores due now; Select ticks one off.
static Window *s_jobs;
static MenuLayer *s_jobs_menu;

static uint16_t jobs_rows(MenuLayer *m, uint16_t s, void *ctx) { return s_chore_count > 0 ? s_chore_count : 1; }

static void jobs_draw(GContext *g, const Layer *cell, MenuIndex *i, void *ctx) {
  if (s_chore_count == 0) {
    menu_cell_basic_draw(g, cell, "All done", "Nothing due now", NULL);
    return;
  }
  Chore *c = &s_chores[i->row];
  char sub[16] = "";
  if (c->stars > 0) snprintf(sub, sizeof(sub), "%d star%s", c->stars, c->stars == 1 ? "" : "s");
  menu_cell_basic_draw(g, cell, c->title, sub[0] ? sub : "Select when done", NULL);
}

static void jobs_select(MenuLayer *m, MenuIndex *i, void *ctx) {
  if (s_chore_count == 0) return;
  send_cmd(C_CHORE_DONE, s_chores[i->row].id, NULL, NULL, 0);
  vibes_short_pulse();
  // Gone from the list straight away; the phone sends the new list after.
  for (int k = i->row; k < s_chore_count - 1; k++) s_chores[k] = s_chores[k + 1];
  s_chore_count--;
  menu_layer_reload_data(s_jobs_menu);
}

static void jobs_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  s_jobs_menu = menu_layer_create(layer_get_bounds(root));
  menu_layer_set_callbacks(s_jobs_menu, NULL, (MenuLayerCallbacks){.get_num_rows = jobs_rows, .draw_row = jobs_draw, .select_click = jobs_select});
  menu_layer_set_normal_colors(s_jobs_menu, bg(), fg());
  menu_layer_set_highlight_colors(s_jobs_menu, highlight(), PBL_IF_COLOR_ELSE(GColorWhite, bg()));
  menu_layer_set_click_config_onto_window(s_jobs_menu, w);
  layer_add_child(root, menu_layer_get_layer(s_jobs_menu));
}

static void jobs_unload(Window *w) { menu_layer_destroy(s_jobs_menu); s_jobs_menu = NULL; }

static void open_jobs(void) {
  if (!s_jobs) {
    s_jobs = window_create();
    window_set_window_handlers(s_jobs, (WindowHandlers){.load = jobs_load, .unload = jobs_unload});
  }
  window_set_background_color(s_jobs, bg());
  window_stack_push(s_jobs, true);
}

static void me_action(ActionMenu *menu, const ActionMenuItem *item, void *ctx) {
  int what = (int)action_menu_item_get_action_data(item);
  if (what == 1) open_sos();
  else if (what == 3) open_jobs();
  else if (what == 2) send_cmd(C_SHARING, NULL, NULL, NULL, (s_me_flags & M_SHARING) ? 0 : 1);
}

static void open_me_menu(void) {
  ActionMenuLevel *me = action_menu_level_create(5);
  static char jobs[20];
  if (s_chore_count > 0) {
    snprintf(jobs, sizeof(jobs), "My jobs (%d)", s_chore_count);
    action_menu_level_add_action(me, jobs, me_action, (void *)3);
  }
  ActionMenuLevel *checkin = action_menu_level_create(5);
  action_menu_level_add_child(me, checkin, "Check in");
  action_menu_level_add_action(checkin, "Made it", checkin_preset, "Made it");
  action_menu_level_add_action(checkin, "On my way", checkin_preset, "On my way");
  action_menu_level_add_action(checkin, "Running late", checkin_preset, "Running late");
  action_menu_level_add_action(checkin, "All good", checkin_preset, "All good");
#if defined(PBL_MICROPHONE)
  action_menu_level_add_action(checkin, "Say something…", checkin_preset, NULL);
#endif
  action_menu_level_add_action(me, (s_me_flags & M_SOS) ? "SOS (I'm OK)" : "SOS", me_action, (void *)1);
  action_menu_level_add_action(me, (s_me_flags & M_SHARING) ? "Pause sharing" : "Resume sharing", me_action, (void *)2);
  ActionMenuConfig cfg = menu_config(me);
  action_menu_open(&cfg);
}

// Requests and nudges to one person: kind is sent as is ("pickup", "call", "where", "here",
// and the nudges below). Keep in step with Nudges.kt in the app.
static char s_target_id[16];

static void request_action(ActionMenu *menu, const ActionMenuItem *item, void *ctx) {
  const char *kind = (const char *)action_menu_item_get_action_data(item);
  if (strcmp(kind, "none") == 0) return;
  if (strcmp(kind, "seen") == 0) send_cmd(C_SEEN, s_target_id, NULL, NULL, 0);
  else send_cmd(C_REQUEST, s_target_id, kind, NULL, 0);
}

// The nudges, as words (the watch's fonts don't have every emoji). The last two are for grown-ups only.
static const char *NUDGE_KINDS[] = {"thumbs", "heart", "wave", "smile", "comehome", "cowboy", "alien", "eggplant", "peach"};
static const char *NUDGE_WORDS[] = {"Thumbs up", "Heart", "Wave", "Smile", "Come home", "Cowboy", "Alien", "Eggplant", "Peach"};
#define NUDGES_FOR_ALL 7
#define NUDGES_ALL 9

static void add_nudges(ActionMenuLevel *level, bool adults, int max) {
  int n = adults ? NUDGES_ALL : NUDGES_FOR_ALL;
  if (max < n) n = max;
  for (int i = 0; i < n; i++) action_menu_level_add_action(level, NUDGE_WORDS[i], request_action, (void *)NUDGE_KINDS[i]);
}

// Someone checked in: reply with a nudge (or not).
static void open_checkin_reply(const char *id, bool adults) {
  strncpy(s_target_id, id, sizeof(s_target_id) - 1);
  ActionMenuLevel *root = action_menu_level_create(6);
  add_nudges(root, adults, 4); // thumbs up, heart, wave, smile
  action_menu_level_add_action(root, "Not now", request_action, "none");
  ActionMenuConfig cfg = menu_config(root);
  action_menu_open(&cfg);
}

// Someone asked "where are you?": offer to answer (only they see it).
static void open_where_reply(const char *id, const char *name) {
  strncpy(s_target_id, id, sizeof(s_target_id) - 1);
  static char title[48];
  snprintf(title, sizeof(title), "Tell %s where I am", name);
  ActionMenuLevel *root = action_menu_level_create(2);
  action_menu_level_add_action(root, title, request_action, "here");
  action_menu_level_add_action(root, "Not now", request_action, "none");
  ActionMenuConfig cfg = menu_config(root);
  action_menu_open(&cfg);
}

// ---------------------------------------------------------------- one person
// Their details at the top, then everything you can send them in one list, so a
// nudge is one press away: scroll down and press Select. (Holding Select is SOS.)

static Window *s_person;
static MenuLayer *s_person_menu;
static int s_person_idx;
static int s_sent_row = -1;       // the row showing "Sent", briefly
static AppTimer *s_sent_timer;

// The rows below the details: requests, then nudges (kind, label).
typedef struct { const char *kind, *label; } PersonAction;
static PersonAction s_actions[16];
static int s_action_count;

static void person_build(Person *p) {
  int n = 0;
  if (p->flags & F_SOS) s_actions[n++] = (PersonAction){"seen", "I've seen your SOS"};
  s_actions[n++] = (PersonAction){"pickup", "Pick me up"};
  s_actions[n++] = (PersonAction){"call", "Call me"};
  s_actions[n++] = (PersonAction){"where", "Where are you?"};
  int nudges = (p->flags & F_ADULT) ? NUDGES_ALL : NUDGES_FOR_ALL;
  for (int i = 0; i < nudges && n < 16; i++) s_actions[n++] = (PersonAction){NUDGE_KINDS[i], NUDGE_WORDS[i]};
  s_action_count = n;
}

/** A small drawn picture for each nudge (the watch has no emoji font). */
static void draw_nudge_icon(GContext *g, GRect r, const char *kind, GColor ink) {
  GPoint c = grect_center_point(&r);
  int rad = r.size.w / 2 - 1;
  graphics_context_set_stroke_color(g, ink);
  graphics_context_set_fill_color(g, ink);
  graphics_context_set_stroke_width(g, 2);
  if (strcmp(kind, "heart") == 0) {
    graphics_context_set_fill_color(g, PBL_IF_COLOR_ELSE(GColorRed, ink));
    graphics_fill_circle(g, GPoint(c.x - 5, c.y - 3), 6);
    graphics_fill_circle(g, GPoint(c.x + 5, c.y - 3), 6);
    GPoint tri[] = {{c.x - 11, c.y - 1}, {c.x + 11, c.y - 1}, {c.x, c.y + 11}};
    GPathInfo info = {3, tri};
    GPath *path = gpath_create(&info);
    gpath_draw_filled(g, path);
    gpath_destroy(path);
  } else if (strcmp(kind, "smile") == 0 || strcmp(kind, "alien") == 0 || strcmp(kind, "cowboy") == 0) {
    GColor face = strcmp(kind, "alien") == 0 ? PBL_IF_COLOR_ELSE(GColorGreen, ink) : PBL_IF_COLOR_ELSE(GColorChromeYellow, ink);
    graphics_context_set_fill_color(g, face);
    graphics_fill_circle(g, GPoint(c.x, c.y + 2), rad - 3);
    graphics_context_set_fill_color(g, PBL_IF_COLOR_ELSE(GColorBlack, GColorBlack));
    graphics_fill_circle(g, GPoint(c.x - 4, c.y), 2);
    graphics_fill_circle(g, GPoint(c.x + 4, c.y), 2);
    graphics_fill_rect(g, GRect(c.x - 4, c.y + 6, 9, 2), 1, GCornersAll);
    if (strcmp(kind, "cowboy") == 0) { // a hat
      graphics_context_set_fill_color(g, PBL_IF_COLOR_ELSE(GColorWindsorTan, ink));
      graphics_fill_rect(g, GRect(c.x - 12, c.y - 7, 25, 3), 1, GCornersAll);
      graphics_fill_rect(g, GRect(c.x - 6, c.y - 13, 13, 7), 2, GCornersTop);
    }
  } else if (strcmp(kind, "thumbs") == 0) {
    graphics_context_set_fill_color(g, PBL_IF_COLOR_ELSE(GColorChromeYellow, ink));
    graphics_fill_rect(g, GRect(c.x - 7, c.y - 1, 15, 12), 3, GCornersAll); // fist
    graphics_fill_rect(g, GRect(c.x - 5, c.y - 12, 6, 13), 3, GCornersAll); // thumb
    graphics_context_set_fill_color(g, ink);
    graphics_fill_rect(g, GRect(c.x - 12, c.y, 4, 11), 1, GCornersAll);     // cuff
  } else if (strcmp(kind, "wave") == 0) {
    graphics_context_set_fill_color(g, PBL_IF_COLOR_ELSE(GColorChromeYellow, ink));
    graphics_fill_circle(g, GPoint(c.x, c.y + 5), 7);
    for (int f = 0; f < 4; f++) graphics_fill_rect(g, GRect(c.x - 7 + f * 4, c.y - 9, 3, 12), 1, GCornersAll);
    graphics_fill_rect(g, GRect(c.x + 7, c.y - 2, 3, 8), 1, GCornersAll);
  } else if (strcmp(kind, "comehome") == 0) {
    GPoint roof[] = {{c.x - 12, c.y}, {c.x, c.y - 11}, {c.x + 12, c.y}};
    GPathInfo info = {3, roof};
    GPath *path = gpath_create(&info);
    graphics_context_set_fill_color(g, PBL_IF_COLOR_ELSE(GColorRoseVale, ink));
    gpath_draw_filled(g, path);
    gpath_destroy(path);
    graphics_context_set_fill_color(g, PBL_IF_COLOR_ELSE(GColorChromeYellow, ink));
    graphics_fill_rect(g, GRect(c.x - 8, c.y, 17, 11), 0, GCornerNone);
    graphics_context_set_fill_color(g, PBL_IF_COLOR_ELSE(GColorWindsorTan, bg()));
    graphics_fill_rect(g, GRect(c.x - 2, c.y + 4, 5, 7), 0, GCornerNone);
  } else if (strcmp(kind, "eggplant") == 0 || strcmp(kind, "peach") == 0) {
    bool egg = kind[0] == 'e';
    graphics_context_set_fill_color(g, egg ? PBL_IF_COLOR_ELSE(GColorPurple, ink) : PBL_IF_COLOR_ELSE(GColorRajah, ink));
    if (egg) { graphics_fill_circle(g, GPoint(c.x + 2, c.y + 4), 7); graphics_fill_circle(g, GPoint(c.x - 3, c.y - 2), 5); }
    else graphics_fill_circle(g, GPoint(c.x, c.y + 2), 9);
    graphics_context_set_fill_color(g, PBL_IF_COLOR_ELSE(GColorIslamicGreen, ink));
    graphics_fill_rect(g, GRect(c.x - 6, c.y - 12, 8, 4), 2, GCornersAll);
  } else {
    // Requests: a ring with a mark (? for where, ! for SOS seen, a dot otherwise).
    graphics_draw_circle(g, c, rad - 2);
    const char *mark = strcmp(kind, "where") == 0 ? "?" : strcmp(kind, "seen") == 0 ? "!" : strcmp(kind, "call") == 0 ? "C" : "P";
    graphics_context_set_text_color(g, ink);
    graphics_draw_text(g, mark, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD), GRect(r.origin.x, c.y - 13, r.size.w, 20), GTextOverflowModeFill, GTextAlignmentCenter, NULL);
  }
}

static uint16_t person_rows(MenuLayer *m, uint16_t section, void *ctx) { return 1 + s_action_count; }

static int16_t person_row_height(MenuLayer *m, MenuIndex *i, void *ctx) {
  if (i->row != 0) return 40;
  // The details: as tall as they need (name, status, detail).
  Person *p = &s_people[s_person_idx];
  GRect b = layer_get_bounds(menu_layer_get_layer(m));
  int w = b.size.w - 2 * PBL_IF_ROUND_ELSE(24, 6);
  GSize st = graphics_text_layout_get_content_size(p->status, fonts_get_system_font(FONT_KEY_GOTHIC_24), GRect(0, 0, w, 200), GTextOverflowModeWordWrap, GTextAlignmentLeft);
  GSize de = p->detail[0] ? graphics_text_layout_get_content_size(p->detail, fonts_get_system_font(FONT_KEY_GOTHIC_18), GRect(0, 0, w, 200), GTextOverflowModeWordWrap, GTextAlignmentLeft) : GSize(0, 0);
  return PBL_IF_ROUND_ELSE(30, 6) + 32 + st.h + 4 + de.h + 10;
}

static void person_draw_row(GContext *g, const Layer *cell, MenuIndex *i, void *ctx) {
  GRect b = layer_get_bounds(cell);
  Person *p = &s_people[s_person_idx];
  bool selected = menu_cell_layer_is_highlighted(cell);
  GColor ink = selected ? PBL_IF_COLOR_ELSE(GColorWhite, bg()) : fg();
  int inset = PBL_IF_ROUND_ELSE(24, 6);
  if (i->row == 0) {
    int w = b.size.w - 2 * inset;
    int y = PBL_IF_ROUND_ELSE(30, 6);
    GTextAlignment al = PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft);
    graphics_context_set_text_color(g, accent());
    graphics_draw_text(g, p->name, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD), GRect(inset, y - 4, w, 32), GTextOverflowModeTrailingEllipsis, al, NULL);
    graphics_context_set_text_color(g, fg());
    GSize st = graphics_text_layout_get_content_size(p->status, fonts_get_system_font(FONT_KEY_GOTHIC_24), GRect(0, 0, w, 200), GTextOverflowModeWordWrap, al);
    graphics_draw_text(g, p->status, fonts_get_system_font(FONT_KEY_GOTHIC_24), GRect(inset, y + 30, w, st.h + 4), GTextOverflowModeWordWrap, al, NULL);
    if (p->detail[0]) graphics_draw_text(g, p->detail, fonts_get_system_font(FONT_KEY_GOTHIC_18), GRect(inset, y + 34 + st.h, w, 200), GTextOverflowModeWordWrap, al, NULL);
    // A hint that there's more below.
    graphics_context_set_fill_color(g, accent());
    graphics_fill_rect(g, GRect(b.size.w / 2 - 10, b.size.h - 4, 20, 3), 1, GCornersAll);
    return;
  }
  PersonAction *a = &s_actions[i->row - 1];
  draw_nudge_icon(g, GRect(inset + 2, (b.size.h - 28) / 2, 28, 28), a->kind, ink);
  graphics_context_set_text_color(g, ink);
  const char *label = (i->row == s_sent_row) ? "Sent" : a->label;
  graphics_draw_text(g, label, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD), GRect(inset + 38, 4, b.size.w - inset * 2 - 38, 30),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
}

static void sent_clear(void *ctx) {
  s_sent_timer = NULL;
  s_sent_row = -1;
  if (s_person_menu) menu_layer_reload_data(s_person_menu);
}

static void person_select(ClickRecognizerRef r, void *ctx) {
  int row = menu_layer_get_selected_index(s_person_menu).row;
  if (row == 0) { menu_layer_set_selected_next(s_person_menu, false, MenuRowAlignCenter, true); return; }
  PersonAction *a = &s_actions[row - 1];
  strncpy(s_target_id, s_people[s_person_idx].id, sizeof(s_target_id) - 1);
  if (strcmp(a->kind, "seen") == 0) send_cmd(C_SEEN, s_target_id, NULL, NULL, 0);
  else send_cmd(C_REQUEST, s_target_id, a->kind, NULL, 0);
  vibes_short_pulse();
  s_sent_row = row;
  menu_layer_reload_data(s_person_menu);
  if (s_sent_timer) app_timer_cancel(s_sent_timer);
  s_sent_timer = app_timer_register(1500, sent_clear, NULL);
}

static void person_up(ClickRecognizerRef r, void *ctx) { menu_layer_set_selected_next(s_person_menu, true, MenuRowAlignCenter, true); }
static void person_down(ClickRecognizerRef r, void *ctx) { menu_layer_set_selected_next(s_person_menu, false, MenuRowAlignCenter, true); }

static void person_clicks(void *ctx) {
  window_single_repeating_click_subscribe(BUTTON_ID_UP, 150, person_up);
  window_single_repeating_click_subscribe(BUTTON_ID_DOWN, 150, person_down);
  window_single_click_subscribe(BUTTON_ID_SELECT, person_select);
  hold_subscribe(700);
}

static void person_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  s_person_menu = menu_layer_create(layer_get_bounds(root));
  menu_layer_set_callbacks(s_person_menu, NULL, (MenuLayerCallbacks){
                                                    .get_num_rows = person_rows,
                                                    .get_cell_height = person_row_height,
                                                    .draw_row = person_draw_row,
                                                });
  menu_layer_set_normal_colors(s_person_menu, bg(), fg());
  menu_layer_set_highlight_colors(s_person_menu, highlight(), PBL_IF_COLOR_ELSE(GColorWhite, bg()));
  layer_add_child(root, menu_layer_get_layer(s_person_menu));
}

static void person_show(void) {
  if (!s_person_menu) return;
  person_build(&s_people[s_person_idx]);
  menu_layer_reload_data(s_person_menu);
}

static void person_appear(Window *w) { hold_attach(w); }

static void person_unload(Window *w) {
  if (s_sent_timer) { app_timer_cancel(s_sent_timer); s_sent_timer = NULL; }
  menu_layer_destroy(s_person_menu);
  s_person_menu = NULL;
}

static void open_person(int idx) {
  s_person_idx = idx;
  s_sent_row = -1;
  if (!s_person) {
    s_person = window_create();
    window_set_click_config_provider(s_person, person_clicks);
    window_set_window_handlers(s_person, (WindowHandlers){.load = person_load, .appear = person_appear, .unload = person_unload});
  }
  window_set_background_color(s_person, bg());
  window_stack_push(s_person, true);
  person_show();
  // Start on the details; Down goes to the first thing to send.
  menu_layer_set_selected_index(s_person_menu, MenuIndex(0, 0), MenuRowAlignTop, false);
}

// ---------------------------------------------------------------- radar and maps
// Up past the top of the list: a radar (you in the middle, everyone in their real
// direction, distances squeezed so far-away people still fit), then a street map
// of each person, one per press of Up. Down goes back the way you came.

static Window *s_view;
static Layer *s_view_layer;
static int s_view_idx;      // -1 radar, else the person whose map this is
static int s_radar_pick;    // the person the radar's footer describes
static GBitmap *s_map;
static int s_map_zoom = 16, s_map_got, s_map_total;
static char s_map_id[16], s_map_msg[48];
static GSize s_map_size;

static void map_free(void) {
  if (s_map) { gbitmap_destroy(s_map); s_map = NULL; }
  s_map_got = s_map_total = 0;
  s_map_id[0] = 0;
}

static int inbox_chunk(void) {
  uint32_t max = app_message_inbox_size_maximum();
  return (int)(max > 2200 ? 2048 : max - 150);
}

static void map_request(void) {
  map_free();
  Person *p = &s_people[s_view_idx];
  strncpy(s_map_id, p->id, sizeof(s_map_id) - 1);
  if (p->dist < 0 && !(p->flags & F_ME)) { strcpy(s_map_msg, "Location not shared"); return; }
#if defined(PBL_PLATFORM_APLITE)
  // The original Pebble hasn't the memory for a map picture.
  strcpy(s_map_msg, "Maps need a newer Pebble");
  return;
#endif
  strcpy(s_map_msg, "Loading map…");
  GRect b = layer_get_bounds(s_view_layer);
  // The map fills the screen above the footer (round: the whole face). Smaller if memory is short.
  int w = b.size.w, h = PBL_IF_ROUND_ELSE(b.size.h, b.size.h - 34);
  for (int tries = 0; tries < 3 && !s_map; tries++) {
    s_map = gbitmap_create_blank(GSize(w, h), PBL_IF_COLOR_ELSE(GBitmapFormat8Bit, GBitmapFormat1Bit));
    if (!s_map) { w = w * 3 / 4; h = h * 3 / 4; }
  }
  if (!s_map) { strcpy(s_map_msg, "Not enough memory for a map"); return; }
  s_map_size = GSize(w, h);
  memset(gbitmap_get_data(s_map), PBL_IF_COLOR_ELSE(0xFF, 0xFF), gbitmap_get_bytes_per_row(s_map) * h);
  DictionaryIterator *out;
  if (app_message_outbox_begin(&out) != APP_MSG_OK) { strcpy(s_map_msg, "Couldn't reach your phone"); return; }
  dict_write_int32(out, K_CMD, C_MAP_REQ);
  dict_write_cstring(out, K_ID, p->id);
  dict_write_int32(out, K_W, w);
  dict_write_int32(out, K_H, h);
  dict_write_int32(out, K_STRIDE, gbitmap_get_bytes_per_row(s_map));
  dict_write_int32(out, K_CHUNK, inbox_chunk());
  dict_write_int32(out, K_NUM, s_map_zoom);
  dict_write_cstring(out, K_KIND, PBL_IF_COLOR_ELSE("c", "b"));
  app_message_outbox_send();
}

static void map_piece(DictionaryIterator *it) {
  Tuple *id = dict_find(it, K_ID), *data = dict_find(it, K_DATA);
  if (!s_map || !id || !data || strcmp(id->value->cstring, s_map_id) != 0) return;
  Tuple *zoom = dict_find(it, K_NUM);
  if (zoom && (int)zoom->value->int32 != s_map_zoom) return; // an older request
  int offset = (int)dict_find(it, K_IDX)->value->int32;
  int total = (int)dict_find(it, K_COUNT)->value->int32;
  int size = gbitmap_get_bytes_per_row(s_map) * s_map_size.h;
  if (total != size || offset < 0 || offset + (int)data->length > size) return;
  memcpy(gbitmap_get_data(s_map) + offset, data->value->data, data->length);
  s_map_total = total;
  s_map_got = offset + data->length;
  if (s_view_layer) layer_mark_dirty(s_view_layer);
}

// Distances on a log scale, so someone 300 m away and someone 1,000 km away both
// fit: r = R * log(1 + d/a) / log(1 + max/a), with a = 100 m.
static int32_t s_radar_max;
#define RADAR_A 100

// log2(x) in 1/256ths (x >= 1), good to about 1%: plenty for a picture.
static int32_t log2_fixed(uint32_t x) {
  int msb = 31;
  while (msb > 0 && !(x & (1u << msb))) msb--;
  uint32_t frac = msb >= 8 ? (x >> (msb - 8)) & 0xFF : (x << (8 - msb)) & 0xFF;
  return msb * 256 + (int32_t)frac;
}

// The scale reaches the farthest person, except far-off outliers (more than 10 times
// farther than the next): they sit on the outer ring, and the footer gives their distance.
static void radar_scale(void) {
  int32_t ds[MAX_PEOPLE + MAX_FENCES];
  int n = 0;
  for (int i = 0; i < s_count; i++) if (s_people[i].dist > 0) ds[n++] = s_people[i].dist;
  for (int i = 0; i < s_fence_count; i++) if (s_fences[i].dist > 0) ds[n++] = s_fences[i].dist;
  for (int i = 0; i < n; i++)
    for (int j = i + 1; j < n; j++)
      if (ds[j] < ds[i]) { int32_t t = ds[i]; ds[i] = ds[j]; ds[j] = t; }
  while (n > 1 && ds[n - 2] >= 500 && ds[n - 1] > 10 * ds[n - 2]) n--;
  s_radar_max = n > 0 && ds[n - 1] > 500 ? ds[n - 1] : 500;
}

static int radar_r(int32_t d, int R) {
  if (d <= 0) return 0;
  int32_t num = log2_fixed((uint32_t)(d + RADAR_A) * 16 / RADAR_A) - 4 * 256; // log2(1 + d/a), with 4 extra bits
  int32_t den = log2_fixed((uint32_t)(s_radar_max + RADAR_A) * 16 / RADAR_A) - 4 * 256;
  if (den <= 0) return 0;
  int r = (int)((int64_t)num * R / den);
  return r > R ? R : (r < 0 ? 0 : r);
}

static GPoint radar_point(GPoint c, int r, int bearing) {
  int32_t a = DEG_TO_TRIGANGLE(bearing);
  return GPoint(c.x + (int)(sin_lookup(a) * r / TRIG_MAX_RATIO), c.y - (int)(cos_lookup(a) * r / TRIG_MAX_RATIO));
}

static void draw_footer(GContext *g, GRect b, const char *title, const char *line) {
#if defined(PBL_ROUND)
  int y = b.size.h - 58;
  int inset = 40;
  graphics_context_set_fill_color(g, bg()); // readable over a map
  graphics_fill_rect(g, GRect(inset - 6, y - 1, b.size.w - 2 * inset + 12, 34), 6, GCornersAll);
#else
  int y = b.size.h - 34;
  int inset = 4;
  graphics_context_set_fill_color(g, bg());
  graphics_fill_rect(g, GRect(0, y, b.size.w, 34), 0, GCornerNone);
#endif
  graphics_context_set_text_color(g, accent());
  graphics_draw_text(g, title, fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD), GRect(inset, y - 1, b.size.w - 2 * inset, 16),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
  graphics_context_set_text_color(g, fg());
  graphics_draw_text(g, line, fonts_get_system_font(FONT_KEY_GOTHIC_14), GRect(inset, y + 14, b.size.w - 2 * inset, 18),
                     GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
}

static void draw_person_dot(GContext *g, GPoint p, Person *who, int r, bool picked) {
#if defined(PBL_COLOR)
  graphics_context_set_fill_color(g, GColorFromHEX(who->color));
  graphics_fill_circle(g, p, r);
  graphics_context_set_stroke_color(g, picked ? fg() : state_color(who->state));
  graphics_context_set_stroke_width(g, 2);
  graphics_draw_circle(g, p, r + 1);
  graphics_context_set_text_color(g, GColorWhite);
#else
  graphics_context_set_fill_color(g, fg());
  graphics_fill_circle(g, p, r);
  graphics_context_set_stroke_color(g, fg());
  if (picked) graphics_draw_circle(g, p, r + 2);
  graphics_context_set_text_color(g, bg());
#endif
  graphics_draw_text(g, who->initial, fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD), GRect(p.x - r, p.y - r - 3, 2 * r, 2 * r),
                     GTextOverflowModeFill, GTextAlignmentCenter, NULL);
}

static void draw_radar(GContext *g, GRect b) {
  radar_scale();
  GPoint c = GPoint(b.size.w / 2, PBL_IF_ROUND_ELSE(b.size.h / 2, (b.size.h - 34) / 2 + 2));
  int R = PBL_IF_ROUND_ELSE(b.size.w / 2 - 22, (b.size.h - 34) / 2 - 10);
  if (b.size.w / 2 - 10 < R) R = b.size.w / 2 - 10;
  Person *me = s_count > 0 ? &s_people[0] : NULL;
  if (!me || me->dist != 0) {
    graphics_context_set_text_color(g, fg());
    graphics_draw_text(g, "Your location isn't known yet", fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
                       GRect(20, c.y - 24, b.size.w - 40, 50), GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
    return;
  }
  // Rings at round distances, labelled.
  static const int32_t RINGS[] = {100, 250, 500, 1000, 2000, 5000, 10000, 25000, 50000, 100000, 250000, 500000, 1000000, 2500000};
  int last_r = 0;
  graphics_context_set_stroke_color(g, PBL_IF_COLOR_ELSE(light() ? GColorLightGray : GColorDarkGray, fg()));
  graphics_context_set_stroke_width(g, 1);
  for (unsigned i = 0; i < ARRAY_LENGTH(RINGS); i++) {
    int32_t d = RINGS[i];
    if (d > s_radar_max) break;
    int r = radar_r(d, R);
    if (r - last_r < 16 || r > R - 4) continue;
    graphics_draw_circle(g, c, r);
    char label[20];
    fmt_distance(label, sizeof(label), d);
    graphics_context_set_text_color(g, muted());
    graphics_draw_text(g, label, fonts_get_system_font(FONT_KEY_GOTHIC_09), GRect(c.x + 2, c.y - r - 1, 40, 12),
                       GTextOverflowModeFill, GTextAlignmentLeft, NULL);
    last_r = r;
  }
  graphics_draw_circle(g, c, R);
  graphics_context_set_text_color(g, accent());
  graphics_draw_text(g, "N", fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD), GRect(c.x - 10, c.y - R - 16, 20, 16),
                     GTextOverflowModeFill, GTextAlignmentCenter, NULL);
  // Fences: small hollow rings.
  graphics_context_set_stroke_color(g, accent());
  for (int i = 0; i < s_fence_count; i++) {
    graphics_draw_circle(g, radar_point(c, radar_r(s_fences[i].dist, R), s_fences[i].bearing), 3);
  }
  // You, then everyone else (the picked one last, on top).
  graphics_context_set_fill_color(g, accent());
  graphics_fill_circle(g, c, 4);
  for (int pass = 0; pass < 2; pass++) {
    for (int i = 1; i < s_count; i++) {
      Person *p = &s_people[i];
      if (p->dist < 0 || (pass == 1) != (i == s_radar_pick)) continue;
      bool far = p->dist > s_radar_max;
      GPoint at = radar_point(c, far ? R : radar_r(p->dist, R), p->bearing);
      if (far) {
        // Beyond the scale: a little tick pointing outwards from the rim.
        graphics_context_set_stroke_color(g, fg());
        graphics_context_set_stroke_width(g, 1);
        graphics_draw_line(g, radar_point(c, R - 12, p->bearing), radar_point(c, R - 9, p->bearing));
      }
      draw_person_dot(g, at, p, 7, i == s_radar_pick);
    }
  }
  if (s_radar_pick > 0 && s_radar_pick < s_count) {
    Person *p = &s_people[s_radar_pick];
    char line[80];
    if (p->dist < 0) snprintf(line, sizeof(line), "Location not shared");
    else snprintf(line, sizeof(line), "%s", p->detail[0] ? p->detail : p->status);
    char title[64];
    snprintf(title, sizeof(title), "%s · %s", p->name, p->label);
    draw_footer(g, b, title, line);
  } else {
    draw_footer(g, b, "Radar", "SELECT: next person · UP: maps");
  }
}

static void draw_map(GContext *g, GRect b) {
  Person *p = &s_people[s_view_idx];
  if (s_map && s_map_got > 0) {
    GRect at = GRect((b.size.w - s_map_size.w) / 2, PBL_IF_ROUND_ELSE((b.size.h - s_map_size.h) / 2, 0), s_map_size.w, s_map_size.h);
    graphics_draw_bitmap_in_rect(g, s_map, at);
    // They're in the middle.
    draw_person_dot(g, GPoint(at.origin.x + at.size.w / 2, at.origin.y + at.size.h / 2), p, 8, false);
  } else {
    graphics_context_set_text_color(g, fg());
    graphics_draw_text(g, s_map_msg, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD), GRect(20, b.size.h / 2 - 30, b.size.w - 40, 44),
                       GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
  }
  char title[64], line[48];
  snprintf(title, sizeof(title), "%s · %s", (p->flags & F_ME) ? "You" : p->name, p->label);
  if (s_map && s_map_total > 0 && s_map_got < s_map_total) snprintf(line, sizeof(line), "Loading… %d%%", s_map_got * 100 / s_map_total);
  else snprintf(line, sizeof(line), "SELECT: zoom %s", s_map_zoom == 16 ? "out" : (s_map_zoom == 12 ? "in" : "out"));
  draw_footer(g, b, title, line);
}

static void draw_view(Layer *layer, GContext *g) {
  GRect b = layer_get_bounds(layer);
  graphics_context_set_fill_color(g, bg());
  graphics_fill_rect(g, b, 0, GCornerNone);
  if (s_view_idx < 0) draw_radar(g, b);
  else draw_map(g, b);
}

static void view_show(void) {
  if (s_view_idx >= 0) map_request();
  else map_free();
  layer_mark_dirty(s_view_layer);
}

static void view_up(ClickRecognizerRef r, void *ctx) {
  if (s_view_idx + 1 < s_count) { s_view_idx++; view_show(); }
  else vibes_short_pulse(); // the last one
}

static void view_down(ClickRecognizerRef r, void *ctx) {
  if (s_view_idx < 0) { window_stack_remove(s_view, true); return; }
  s_view_idx--;
  view_show();
}

static void view_select(ClickRecognizerRef r, void *ctx) {
  if (s_view_idx < 0) {
    // The next person with a location.
    for (int step = 1; step < s_count; step++) {
      int i = (s_radar_pick + step - 1) % (s_count - 1) + 1;
      if (s_people[i].dist >= 0) { s_radar_pick = i; break; }
    }
    layer_mark_dirty(s_view_layer);
  } else {
    s_map_zoom = s_map_zoom == 16 ? 14 : (s_map_zoom == 14 ? 12 : 16);
    map_request();
    layer_mark_dirty(s_view_layer);
  }
}

static void view_clicks(void *ctx) {
  window_single_repeating_click_subscribe(BUTTON_ID_UP, 300, view_up);
  window_single_repeating_click_subscribe(BUTTON_ID_DOWN, 300, view_down);
  window_single_click_subscribe(BUTTON_ID_SELECT, view_select);
  hold_subscribe(700);
}

static void view_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  s_view_layer = layer_create(layer_get_bounds(root));
  layer_set_update_proc(s_view_layer, draw_view);
  layer_add_child(root, s_view_layer);
}

static void view_appear(Window *w) { hold_attach(w); }

static void view_unload(Window *w) {
  map_free();
  layer_destroy(s_view_layer);
  s_view_layer = NULL;
}

static void open_view(void) {
  if (!s_view) {
    s_view = window_create();
    window_set_click_config_provider(s_view, view_clicks);
    window_set_window_handlers(s_view, (WindowHandlers){.load = view_load, .appear = view_appear, .unload = view_unload});
  }
  s_view_idx = -1;
  if (s_radar_pick <= 0 || s_radar_pick >= s_count) s_radar_pick = s_count > 1 ? 1 : 0;
  window_set_background_color(s_view, bg());
  window_stack_push(s_view, true);
}

// ---------------------------------------------------------------- the list

// Row 0 is you; the others follow (the phone sends you first).
static uint16_t menu_rows(MenuLayer *m, uint16_t section, void *ctx) { return s_count > 0 ? s_count : 1; }

static int16_t menu_row_height(MenuLayer *m, MenuIndex *idx, void *ctx) {
  return PBL_IF_ROUND_ELSE(menu_layer_is_index_selected(m, idx) ? 64 : 44, 52);
}

#if !defined(PBL_ROUND)
#if defined(PBL_COLOR)
// One small bitmap, refilled with whoever's emoji is being drawn.
static GBitmap *s_icon_bmp;
static bool draw_icon(GContext *g, GPoint c, Person *p) {
  if (!p->has_icon) return false;
  if (!s_icon_bmp) s_icon_bmp = gbitmap_create_blank(GSize(ICON_PX, ICON_PX), GBitmapFormat8Bit);
  if (!s_icon_bmp) return false;
  uint8_t *dst = gbitmap_get_data(s_icon_bmp);
  int stride = gbitmap_get_bytes_per_row(s_icon_bmp);
  for (int y = 0; y < ICON_PX; y++) memcpy(dst + y * stride, p->icon + y * ICON_PX, ICON_PX);
  graphics_context_set_compositing_mode(g, GCompOpSet);
  graphics_draw_bitmap_in_rect(g, s_icon_bmp, GRect(c.x - ICON_PX / 2, c.y - ICON_PX / 2, ICON_PX, ICON_PX));
  graphics_context_set_compositing_mode(g, GCompOpAssign);
  return true;
}
#endif

static void draw_avatar(GContext *g, GRect r, Person *p, bool selected) {
  // A little room from the screen's edge.
  GPoint c = GPoint(r.origin.x + 24, r.origin.y + r.size.h / 2);
#if defined(PBL_COLOR)
  graphics_context_set_fill_color(g, p->state == 'a' ? GColorDarkGray : GColorFromHEX(p->color));
  graphics_fill_circle(g, c, 14);
  graphics_context_set_stroke_color(g, selected ? GColorWhite : state_color(p->state));
  graphics_context_set_stroke_width(g, 3);
  graphics_draw_circle(g, c, 16);
  graphics_context_set_text_color(g, GColorWhite);
  if (draw_icon(g, c, p)) return;
#else
  GColor on = selected ? bg() : fg();
  graphics_context_set_stroke_color(g, on);
  graphics_context_set_stroke_width(g, (p->flags & F_SOS) ? 3 : 1);
  graphics_draw_circle(g, c, 15);
  if (p->state == 'd' || p->state == 'm') graphics_draw_circle(g, c, 17);
  graphics_context_set_text_color(g, on);
#endif
  graphics_draw_text(g, p->initial, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD), GRect(c.x - 14, c.y - 12, 30, 22),
                     GTextOverflowModeFill, GTextAlignmentCenter, NULL);
}
#endif

static void menu_draw_row(GContext *g, const Layer *cell, MenuIndex *idx, void *ctx) {
  GRect b = layer_get_bounds(cell);
  if (s_count == 0) {
    menu_cell_basic_draw(g, cell, s_loaded ? "Nobody yet" : "Loading…", s_me_status, NULL);
    return;
  }
  Person *p = &s_people[idx->row];
  bool selected = menu_cell_layer_is_highlighted(cell);
#if defined(PBL_ROUND)
  // Round: centred text, no avatar (space is tight).
  menu_cell_basic_draw(g, cell, (p->flags & F_ME) ? "You" : p->name, (p->flags & F_ME) ? s_me_status : p->status, NULL);
  (void)b; (void)selected;
#else
  draw_avatar(g, b, p, selected);
  GColor text = selected ? GColorWhite : fg();
#if !defined(PBL_COLOR)
  text = selected ? bg() : fg();
#endif
  graphics_context_set_text_color(g, text);
  graphics_draw_text(g, (p->flags & F_ME) ? "You" : p->name, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD),
                     GRect(50, -2, b.size.w - 56, 28), GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
  graphics_draw_text(g, (p->flags & F_ME) ? s_me_status : p->status, fonts_get_system_font(FONT_KEY_GOTHIC_18),
                     GRect(50, 24, b.size.w - 56, 24), GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
#endif
}

static void list_select(ClickRecognizerRef r, void *ctx) {
  if (s_count == 0) {
    send_cmd(C_HELLO, NULL, PBL_IF_COLOR_ELSE("c", NULL), NULL, battery_state_service_peek().charge_percent);
    return;
  }
  int row = menu_layer_get_selected_index(s_menu).row;
  if (s_people[row].flags & F_ME) open_me_menu();
  else open_person(row);
}

static void list_up(ClickRecognizerRef r, void *ctx) {
  // Up past the top: the radar.
  if (menu_layer_get_selected_index(s_menu).row == 0) open_view();
  else menu_layer_set_selected_next(s_menu, true, PBL_IF_ROUND_ELSE(MenuRowAlignCenter, MenuRowAlignCenter), true);
}

static void list_down(ClickRecognizerRef r, void *ctx) {
  menu_layer_set_selected_next(s_menu, false, MenuRowAlignCenter, true);
}

static void list_clicks(void *ctx) {
  window_single_repeating_click_subscribe(BUTTON_ID_UP, 150, list_up);
  window_single_repeating_click_subscribe(BUTTON_ID_DOWN, 150, list_down);
  window_single_click_subscribe(BUTTON_ID_SELECT, list_select);
  hold_subscribe(700);
}

static void apply_theme(void) {
  if (!s_menu) return;
  window_set_background_color(s_main, bg());
  menu_layer_set_normal_colors(s_menu, bg(), fg());
  menu_layer_set_highlight_colors(s_menu, highlight(), PBL_IF_COLOR_ELSE(GColorWhite, bg()));
  menu_layer_reload_data(s_menu);
}

static void main_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  GRect b = layer_get_bounds(root);
  s_menu = menu_layer_create(b);
  menu_layer_set_callbacks(s_menu, NULL, (MenuLayerCallbacks){
                                             .get_num_rows = menu_rows,
                                             .get_cell_height = menu_row_height,
                                             .draw_row = menu_draw_row,
                                         });
  // Our own buttons (rather than the menu's) so Up at the top opens the radar and holding Select starts an SOS.
  window_set_click_config_provider(w, list_clicks);
  layer_add_child(root, menu_layer_get_layer(s_menu));
  apply_theme();
}

static void main_appear(Window *w) { hold_attach(w); }

static void main_unload(Window *w) { menu_layer_destroy(s_menu); }

// ---------------------------------------------------------------- receiving

static void copy_str(char *dst, size_t n, DictionaryIterator *it, uint32_t key) {
  Tuple *t = dict_find(it, key);
  if (t && t->type == TUPLE_CSTRING) {
    strncpy(dst, t->value->cstring, n - 1);
    dst[n - 1] = 0;
  }
}

static int get_int(DictionaryIterator *it, uint32_t key) {
  Tuple *t = dict_find(it, key);
  return t ? (int)t->value->int32 : 0;
}

static void update_worker(void) {
  bool want = (s_me_flags & M_FALLS) != 0;
  persist_write_bool(P_FALLS_ON, want);
  if (want && !app_worker_is_running()) app_worker_launch();
  if (!want && app_worker_is_running()) app_worker_kill();
}

static void inbox(DictionaryIterator *it, void *ctx) {
  int cmd = get_int(it, K_CMD);
  if (cmd == C_PERSON) {
    int idx = get_int(it, K_IDX), count = get_int(it, K_COUNT);
    if (count == 0) { s_count = 0; s_loaded = true; menu_layer_reload_data(s_menu); return; }
    if (idx < 0 || idx >= MAX_PEOPLE) return;
    Person *p = &s_people[idx];
    memset(p, 0, sizeof(*p));
    copy_str(p->id, sizeof(p->id), it, K_ID);
    copy_str(p->name, sizeof(p->name), it, K_NAME);
    copy_str(p->initial, sizeof(p->initial), it, K_INITIAL);
    copy_str(p->status, sizeof(p->status), it, K_STATUS);
    copy_str(p->detail, sizeof(p->detail), it, K_TEXT);
    copy_str(p->label, sizeof(p->label), it, K_LABEL);
    char state[4] = "t";
    copy_str(state, sizeof(state), it, K_STATE);
    p->state = state[0];
    p->color = (uint32_t)get_int(it, K_COLOR);
    p->flags = (uint8_t)get_int(it, K_FLAGS);
    p->bearing = (int16_t)get_int(it, K_BEARING);
    p->dist = dict_find(it, K_DIST) ? get_int(it, K_DIST) : -1;
#if defined(PBL_COLOR)
    Tuple *icon = dict_find(it, K_DATA);
    if (icon && icon->length == ICON_PX * ICON_PX) { memcpy(p->icon, icon->value->data, ICON_PX * ICON_PX); p->has_icon = true; }
#endif
    if (idx + 1 > s_count || idx == 0) s_count = idx + 1;
    if (count < s_count) s_count = count > MAX_PEOPLE ? MAX_PEOPLE : count;
    if (idx + 1 >= count) s_loaded = true;
    menu_layer_reload_data(s_menu);
    if (s_person && window_stack_contains_window(s_person) && idx == s_person_idx) person_show();
    if (s_view_layer && s_view_idx < 0) layer_mark_dirty(s_view_layer);
  } else if (cmd == C_CHORE) {
    int idx = get_int(it, K_IDX), count = get_int(it, K_COUNT);
    s_chore_count = count < MAX_CHORES ? count : MAX_CHORES;
    if (idx >= 0 && idx < s_chore_count) {
      Chore *c = &s_chores[idx];
      copy_str(c->id, sizeof(c->id), it, K_ID);
      copy_str(c->title, sizeof(c->title), it, K_NAME);
      c->stars = get_int(it, K_NUM);
    }
    if (s_jobs_menu) menu_layer_reload_data(s_jobs_menu);
  } else if (cmd == C_FENCE) {
    int idx = get_int(it, K_IDX), count = get_int(it, K_COUNT);
    s_fence_count = count < MAX_FENCES ? count : MAX_FENCES;
    if (idx < 0 || idx >= s_fence_count) return;
    Fence *f = &s_fences[idx];
    copy_str(f->name, sizeof(f->name), it, K_NAME);
    f->bearing = (int16_t)get_int(it, K_BEARING);
    f->dist = get_int(it, K_DIST);
    f->radius = get_int(it, K_RADIUS);
    if (s_view_layer && s_view_idx < 0) layer_mark_dirty(s_view_layer);
  } else if (cmd == C_ME) {
    bool was_light = light();
    s_me_flags = get_int(it, K_FLAGS);
    persist_write_int(P_ME_FLAGS, s_me_flags);
    copy_str(s_me_status, sizeof(s_me_status), it, K_TEXT);
    copy_str(s_me_name, sizeof(s_me_name), it, K_NAME);
    update_worker();
    sos_show_state();
    if (was_light != light()) apply_theme();
    menu_layer_reload_data(s_menu);
  } else if (cmd == C_MAP) {
    map_piece(it);
  } else if (cmd == C_MAP_FAIL) {
    Tuple *id = dict_find(it, K_ID);
    if (id && strcmp(id->value->cstring, s_map_id) == 0) {
      copy_str(s_map_msg, sizeof(s_map_msg), it, K_TEXT);
      if (s_map) { gbitmap_destroy(s_map); s_map = NULL; }
      s_map_got = s_map_total = 0;
      if (s_view_layer) layer_mark_dirty(s_view_layer);
    }
  } else if (cmd == C_ALERT) {
    char kind[16] = "", id[16] = "", name[25] = "", text[96] = "";
    copy_str(kind, sizeof(kind), it, K_KIND);
    copy_str(id, sizeof(id), it, K_ID);
    copy_str(name, sizeof(name), it, K_NAME);
    copy_str(text, sizeof(text), it, K_TEXT);
    if (strcmp(kind, "sos") == 0) {
      open_alarm(id, name, text);
    } else if (strcmp(kind, "where") == 0) {
      vibes_double_pulse();
      light_enable_interaction();
      open_where_reply(id, name);
    } else if (strcmp(kind, "checkin") == 0 || strcmp(kind, "checkin_a") == 0) {
      // "checkin_a": they're a grown-up too, so all the nudges are on offer.
      char buf[sizeof(name) + sizeof(text) + 2];
      snprintf(buf, sizeof(buf), "%s %s", name, text);
      vibes_short_pulse();
      light_enable_interaction();
      toast(buf);
      open_checkin_reply(id, strcmp(kind, "checkin_a") == 0);
    } else {
      if (strcmp(kind, "sos_end") == 0 && s_alarm && window_stack_contains_window(s_alarm) && strcmp(id, s_alarm_id) == 0) {
        window_stack_remove(s_alarm, true);
      }
      char buf[sizeof(name) + sizeof(text) + 2];
      snprintf(buf, sizeof(buf), "%s %s", name, text);
      vibes_short_pulse();
      toast(buf);
    }
  } else if (cmd == C_RESULT) {
    char text[64] = "";
    copy_str(text, sizeof(text), it, K_TEXT);
    toast(text);
  }
}

static void outbox_failed(DictionaryIterator *it, AppMessageResult reason, void *ctx) {
  toast("Couldn't reach your phone");
}

// ---------------------------------------------------------------- battery

static int s_last_battery = -1;

static void battery_changed(BatteryChargeState s) {
  if (s_last_battery < 0 || s.charge_percent / 10 != s_last_battery / 10) {
    s_last_battery = s.charge_percent;
    send_cmd(C_BATTERY, NULL, NULL, NULL, s.charge_percent > 0 ? s.charge_percent : 1);
  }
}

// ---------------------------------------------------------------- start

static void say_hello(void *data) { send_cmd(C_HELLO, NULL, PBL_IF_COLOR_ELSE("c", NULL), NULL, battery_state_service_peek().charge_percent); }

int main(void) {
  // The last settings from the phone (theme, Quick Launch SOS), until it sends new ones.
  if (persist_exists(P_ME_FLAGS)) s_me_flags = persist_read_int(P_ME_FLAGS) & ~M_SOS;

  s_main = window_create();
  window_set_window_handlers(s_main, (WindowHandlers){.load = main_load, .appear = main_appear, .unload = main_unload});
  window_stack_push(s_main, true);

  app_message_register_inbox_received(inbox);
  app_message_register_outbox_failed(outbox_failed);
  uint32_t in = app_message_inbox_size_maximum();
#if defined(PBL_PLATFORM_APLITE)
  app_message_open(in > 600 ? 600 : in, 256); // little memory on the original Pebble, and no maps
#else
  app_message_open(in > 2200 ? 2200 : in, 256);
#endif
  battery_state_service_subscribe(battery_changed);

  if (launch_reason() == APP_LAUNCH_WORKER && persist_exists(P_FALL_AT)) {
    // Opened by the fall-detection worker: count down before sending an SOS.
    time_t at = (time_t)persist_read_int(P_FALL_AT);
    persist_delete(P_FALL_AT);
    if (time(NULL) - at < 60) open_fall_countdown();
  } else if (launch_reason() == APP_LAUNCH_QUICK_LAUNCH && (s_me_flags & M_QUICK_SOS)) {
    // Opened with Quick Launch, and asked to: straight to the SOS screen.
    open_sos();
  }
  app_timer_register(300, say_hello, NULL);

  app_event_loop();

  battery_state_service_unsubscribe();
  window_destroy(s_main);
}
