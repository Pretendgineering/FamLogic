// Thereabouts for Pebble: see everyone at a glance, check in, send or answer an
// SOS, ask for a pick-up. The phone app (PebbleKit 2) does all the talking to
// the server; message keys and commands match pebble/PebbleBridge.kt.
#include <pebble.h>

// ---- protocol (keep in step with PebbleBridge.kt) ----
enum { K_CMD = 0, K_IDX, K_COUNT, K_ID, K_NAME, K_STATUS, K_COLOR, K_FLAGS, K_TEXT, K_KIND, K_NUM, K_INITIAL };
enum { C_PERSON = 1, C_ME, C_ALERT, C_RESULT, C_FACE };
enum { C_HELLO = 20, C_CHECKIN, C_SOS, C_SOS_END, C_REQUEST, C_SEEN, C_SHARING, C_BATTERY };
enum { F_ME = 1, F_SOS = 2, F_DRIVING = 4, F_AWAY = 8, F_PLACE = 16 };
enum { M_SHARING = 1, M_SOS = 2, M_HEART_RATE = 4, M_FALLS = 8 };

// Persistent storage keys (shared with the fall-detection worker).
#define P_FALL_AT 1
#define P_FALLS_ON 2

#define MAX_PEOPLE 12
typedef struct {
  char id[16];
  char name[25];
  char initial[5];
  char status[41];
  char detail[72];
  uint32_t color;
  uint8_t flags;
} Person;

static Person s_people[MAX_PEOPLE];
static int s_count;          // people received so far (me included, first)
static bool s_loaded;        // a full list has arrived
static int s_me_flags;
static char s_me_status[64] = "Connecting to your phone…";
static char s_me_name[25];

static Window *s_main;
static MenuLayer *s_menu;
static TextLayer *s_empty;

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
  s_toast_text = text_layer_create(GRect(8, b.size.h / 2 - 40, b.size.w - 16, 80));
  text_layer_set_text_alignment(s_toast_text, GTextAlignmentCenter);
  text_layer_set_font(s_toast_text, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD));
  text_layer_set_background_color(s_toast_text, GColorClear);
  text_layer_set_text_color(s_toast_text, GColorWhite);
  text_layer_set_overflow_mode(s_toast_text, GTextOverflowModeWordWrap);
  layer_add_child(root, text_layer_get_layer(s_toast_text));
}

static void toast_unload(Window *w) { text_layer_destroy(s_toast_text); }

static void toast(const char *text) {
  strncpy(s_toast_buf, text, sizeof(s_toast_buf) - 1);
  if (!s_toast) {
    s_toast = window_create();
    window_set_background_color(s_toast, PBL_IF_COLOR_ELSE(GColorIndigo, GColorBlack));
    window_set_window_handlers(s_toast, (WindowHandlers){.load = toast_load, .unload = toast_unload});
  }
  if (!window_stack_contains_window(s_toast)) window_stack_push(s_toast, true);
  text_layer_set_text(s_toast_text, s_toast_buf);
  if (s_toast_timer) app_timer_cancel(s_toast_timer);
  s_toast_timer = app_timer_register(2500, toast_hide, NULL);
}

// ---------------------------------------------------------------- SOS (send)

#define SOS_HOLD_SECONDS 3
static Window *s_sos;
static TextLayer *s_sos_title, *s_sos_text;
static AppTimer *s_sos_timer;
static int s_sos_left;
static bool s_sos_holding;
static char s_sos_buf[40];

static void sos_show_state(void) {
  // "SOS is on" doesn't fit the big font: the smaller one while it's on.
  text_layer_set_font(s_sos_title, fonts_get_system_font((s_me_flags & M_SOS) ? FONT_KEY_GOTHIC_28_BOLD : FONT_KEY_BITHAM_42_BOLD));
  if (s_me_flags & M_SOS) {
    text_layer_set_text(s_sos_title, "SOS is on");
    text_layer_set_text(s_sos_text, "Your family has been alerted.\n\nHold SELECT to say you're OK.");
  } else if (s_sos_holding) {
    snprintf(s_sos_buf, sizeof(s_sos_buf), "Keep holding… %d", s_sos_left);
    text_layer_set_text(s_sos_title, "SOS");
    text_layer_set_text(s_sos_text, s_sos_buf);
  } else {
    text_layer_set_text(s_sos_title, "SOS");
    text_layer_set_text(s_sos_text, "Hold SELECT for 3 seconds to alert everyone.\nLet go to cancel.");
  }
}

static void sos_tick(void *data) {
  s_sos_timer = NULL;
  if (!s_sos_holding) return;
  s_sos_left--;
  if (s_sos_left > 0) {
    vibes_short_pulse();
    sos_show_state();
    s_sos_timer = app_timer_register(1000, sos_tick, NULL);
    return;
  }
  s_sos_holding = false;
  vibes_long_pulse();
  if (s_me_flags & M_SOS) {
    send_cmd(C_SOS_END, NULL, NULL, NULL, 0);
    s_me_flags &= ~M_SOS;
  } else {
    send_cmd(C_SOS, NULL, "watch", NULL, heart_rate());
    s_me_flags |= M_SOS;
  }
  sos_show_state();
}

static void sos_down(ClickRecognizerRef r, void *ctx) {
  s_sos_holding = true;
  // Ending needs a shorter hold than starting.
  s_sos_left = (s_me_flags & M_SOS) ? 2 : SOS_HOLD_SECONDS;
  vibes_short_pulse();
  sos_show_state();
  if (s_sos_timer) app_timer_cancel(s_sos_timer);
  s_sos_timer = app_timer_register(1000, sos_tick, NULL);
}

static void sos_up(ClickRecognizerRef r, void *ctx) {
  if (s_sos_holding) {
    s_sos_holding = false;
    if (s_sos_timer) { app_timer_cancel(s_sos_timer); s_sos_timer = NULL; }
    sos_show_state();
  }
}

static void sos_clicks(void *ctx) { window_raw_click_subscribe(BUTTON_ID_SELECT, sos_down, sos_up, NULL); }

static void sos_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  GRect b = layer_get_bounds(root);
  int inset = PBL_IF_ROUND_ELSE(24, 8);
  s_sos_title = text_layer_create(GRect(inset, b.size.h / 2 - 70, b.size.w - 2 * inset, 44));
  s_sos_text = text_layer_create(GRect(inset, b.size.h / 2 - 22, b.size.w - 2 * inset, 90));
  TextLayer *ls[] = {s_sos_title, s_sos_text};
  for (int i = 0; i < 2; i++) {
    text_layer_set_background_color(ls[i], GColorClear);
    text_layer_set_text_color(ls[i], GColorWhite);
    text_layer_set_text_alignment(ls[i], GTextAlignmentCenter);
    layer_add_child(root, text_layer_get_layer(ls[i]));
  }
  text_layer_set_font(s_sos_title, fonts_get_system_font(FONT_KEY_BITHAM_42_BOLD));
  text_layer_set_font(s_sos_text, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
  sos_show_state();
}

static void sos_unload(Window *w) {
  if (s_sos_timer) { app_timer_cancel(s_sos_timer); s_sos_timer = NULL; }
  s_sos_holding = false;
  text_layer_destroy(s_sos_title);
  text_layer_destroy(s_sos_text);
}

static void open_sos(void) {
  if (!s_sos) {
    s_sos = window_create();
    window_set_background_color(s_sos, PBL_IF_COLOR_ELSE(GColorDarkCandyAppleRed, GColorBlack));
    window_set_click_config_provider(s_sos, sos_clicks);
    window_set_window_handlers(s_sos, (WindowHandlers){.load = sos_load, .unload = sos_unload});
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

// ---------------------------------------------------------------- dictation and check-in

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

static void me_action(ActionMenu *menu, const ActionMenuItem *item, void *ctx) {
  int what = (int)action_menu_item_get_action_data(item);
  if (what == 1) open_sos();
  else if (what == 2) send_cmd(C_SHARING, NULL, NULL, NULL, (s_me_flags & M_SHARING) ? 0 : 1);
}

static ActionMenuLevel *s_me_menu, *s_checkin_menu;

static void action_menu_closed(ActionMenu *menu, const ActionMenuItem *performed, void *ctx) {
  action_menu_hierarchy_destroy(action_menu_get_root_level(menu), NULL, NULL);
  s_me_menu = s_checkin_menu = NULL;
}

static void open_me_menu(void) {
  s_me_menu = action_menu_level_create(4);
  s_checkin_menu = action_menu_level_create(5);
  action_menu_level_add_child(s_me_menu, s_checkin_menu, "Check in");
  action_menu_level_add_action(s_checkin_menu, "Made it", checkin_preset, "Made it");
  action_menu_level_add_action(s_checkin_menu, "On my way", checkin_preset, "On my way");
  action_menu_level_add_action(s_checkin_menu, "Running late", checkin_preset, "Running late");
  action_menu_level_add_action(s_checkin_menu, "All good", checkin_preset, "All good");
#if defined(PBL_MICROPHONE)
  action_menu_level_add_action(s_checkin_menu, "Say something…", checkin_preset, NULL);
#endif
  action_menu_level_add_action(s_me_menu, (s_me_flags & M_SOS) ? "SOS (I'm OK)" : "SOS", me_action, (void *)1);
  action_menu_level_add_action(s_me_menu, (s_me_flags & M_SHARING) ? "Pause sharing" : "Resume sharing", me_action, (void *)2);
  ActionMenuConfig cfg = {
      .root_level = s_me_menu,
      .colors = {.background = PBL_IF_COLOR_ELSE(GColorIndigo, GColorBlack), .foreground = GColorWhite},
      .align = ActionMenuAlignCenter,
      .did_close = action_menu_closed,
  };
  action_menu_open(&cfg);
}

// ---------------------------------------------------------------- one person

static Window *s_person;
static TextLayer *s_person_name, *s_person_status, *s_person_detail;
static int s_person_idx;

static void person_action(ActionMenu *menu, const ActionMenuItem *item, void *ctx) {
  int what = (int)action_menu_item_get_action_data(item);
  Person *p = &s_people[s_person_idx];
  if (what == 1) send_cmd(C_REQUEST, p->id, "pickup", NULL, 0);
  else if (what == 2) send_cmd(C_REQUEST, p->id, "call", NULL, 0);
  else if (what == 3) send_cmd(C_SEEN, p->id, NULL, NULL, 0);
}

static void person_select(ClickRecognizerRef r, void *ctx) {
  Person *p = &s_people[s_person_idx];
  ActionMenuLevel *level = action_menu_level_create(3);
  action_menu_level_add_action(level, "Pick me up", person_action, (void *)1);
  action_menu_level_add_action(level, "Call me", person_action, (void *)2);
  if (p->flags & F_SOS) action_menu_level_add_action(level, "I've seen your SOS", person_action, (void *)3);
  ActionMenuConfig cfg = {
      .root_level = level,
      .colors = {.background = PBL_IF_COLOR_ELSE(GColorIndigo, GColorBlack), .foreground = GColorWhite},
      .align = ActionMenuAlignCenter,
      .did_close = action_menu_closed,
  };
  action_menu_open(&cfg);
}

static void person_clicks(void *ctx) { window_single_click_subscribe(BUTTON_ID_SELECT, person_select); }

static void person_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  GRect b = layer_get_bounds(root);
  int inset = PBL_IF_ROUND_ELSE(24, 6);
  int top = PBL_IF_ROUND_ELSE(30, 8);
  s_person_name = text_layer_create(GRect(inset, top, b.size.w - 2 * inset, 32));
  s_person_status = text_layer_create(GRect(inset, top + 34, b.size.w - 2 * inset, 50));
  s_person_detail = text_layer_create(GRect(inset, top + 86, b.size.w - 2 * inset, b.size.h - top - 90));
  TextLayer *ls[] = {s_person_name, s_person_status, s_person_detail};
  for (int i = 0; i < 3; i++) {
    text_layer_set_background_color(ls[i], GColorClear);
    text_layer_set_overflow_mode(ls[i], GTextOverflowModeWordWrap);
    text_layer_set_text_alignment(ls[i], PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft));
    layer_add_child(root, text_layer_get_layer(ls[i]));
  }
  text_layer_set_font(s_person_name, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD));
  text_layer_set_font(s_person_status, fonts_get_system_font(FONT_KEY_GOTHIC_24));
  text_layer_set_font(s_person_detail, fonts_get_system_font(FONT_KEY_GOTHIC_18));
}

static void person_show(void) {
  Person *p = &s_people[s_person_idx];
  text_layer_set_text(s_person_name, p->name);
  text_layer_set_text(s_person_status, p->status);
  text_layer_set_text(s_person_detail, p->detail);
}

static void person_unload(Window *w) {
  text_layer_destroy(s_person_name);
  text_layer_destroy(s_person_status);
  text_layer_destroy(s_person_detail);
}

static void open_person(int idx) {
  s_person_idx = idx;
  if (!s_person) {
    s_person = window_create();
    window_set_click_config_provider(s_person, person_clicks);
    window_set_window_handlers(s_person, (WindowHandlers){.load = person_load, .unload = person_unload});
  }
  window_stack_push(s_person, true);
  person_show();
}

// ---------------------------------------------------------------- the list

// Row 0 is you; the others follow (the phone sends you first).
static uint16_t menu_rows(MenuLayer *m, uint16_t section, void *ctx) { return s_count > 0 ? s_count : 1; }

static int16_t menu_row_height(MenuLayer *m, MenuIndex *idx, void *ctx) {
  return PBL_IF_ROUND_ELSE(menu_layer_is_index_selected(m, idx) ? 64 : 44, 52);
}

#if !defined(PBL_ROUND)
static void draw_avatar(GContext *g, GRect r, Person *p, bool selected) {
  GPoint c = GPoint(r.origin.x + 20, r.origin.y + r.size.h / 2);
#if defined(PBL_COLOR)
  graphics_context_set_fill_color(g, GColorFromHEX(p->color));
  graphics_fill_circle(g, c, 15);
  if (p->flags & F_SOS) {
    graphics_context_set_stroke_color(g, GColorRed);
    graphics_context_set_stroke_width(g, 3);
    graphics_draw_circle(g, c, 17);
  }
  graphics_context_set_text_color(g, GColorWhite);
#else
  graphics_context_set_stroke_color(g, selected ? GColorWhite : GColorBlack);
  graphics_context_set_stroke_width(g, (p->flags & F_SOS) ? 3 : 1);
  graphics_draw_circle(g, c, 15);
  graphics_context_set_text_color(g, selected ? GColorWhite : GColorBlack);
#endif
  graphics_draw_text(g, p->initial, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD), GRect(c.x - 15, c.y - 13, 30, 22),
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
  graphics_context_set_text_color(g, selected ? GColorWhite : GColorBlack);
  graphics_draw_text(g, (p->flags & F_ME) ? "You" : p->name, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD),
                     GRect(42, -2, b.size.w - 46, 28), GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
  graphics_draw_text(g, (p->flags & F_ME) ? s_me_status : p->status, fonts_get_system_font(FONT_KEY_GOTHIC_18),
                     GRect(42, 24, b.size.w - 46, 24), GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
#endif
}

static void menu_select(MenuLayer *m, MenuIndex *idx, void *ctx) {
  if (s_count == 0) {
    send_cmd(C_HELLO, NULL, NULL, NULL, battery_state_service_peek().charge_percent);
    return;
  }
  if (s_people[idx->row].flags & F_ME) open_me_menu();
  else open_person(idx->row);
}

static void menu_long_select(MenuLayer *m, MenuIndex *idx, void *ctx) {
  // Long-press on yourself: straight to SOS.
  if (s_count > 0 && (s_people[idx->row].flags & F_ME)) open_sos();
}

static void main_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  GRect b = layer_get_bounds(root);
  s_menu = menu_layer_create(b);
  menu_layer_set_callbacks(s_menu, NULL, (MenuLayerCallbacks){
                                             .get_num_rows = menu_rows,
                                             .get_cell_height = menu_row_height,
                                             .draw_row = menu_draw_row,
                                             .select_click = menu_select,
                                             .select_long_click = menu_long_select,
                                         });
  menu_layer_set_normal_colors(s_menu, GColorWhite, GColorBlack);
  menu_layer_set_highlight_colors(s_menu, PBL_IF_COLOR_ELSE(GColorIndigo, GColorBlack), GColorWhite);
  menu_layer_set_click_config_onto_window(s_menu, w);
  layer_add_child(root, menu_layer_get_layer(s_menu));
  (void)s_empty;
}

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
    p->color = (uint32_t)get_int(it, K_COLOR);
    p->flags = (uint8_t)get_int(it, K_FLAGS);
    if (idx + 1 > s_count || idx == 0) s_count = idx + 1;
    if (count < s_count) s_count = count > MAX_PEOPLE ? MAX_PEOPLE : count;
    if (idx + 1 >= count) s_loaded = true;
    menu_layer_reload_data(s_menu);
    if (s_person && window_stack_contains_window(s_person) && idx == s_person_idx) person_show();
  } else if (cmd == C_ME) {
    s_me_flags = get_int(it, K_FLAGS);
    copy_str(s_me_status, sizeof(s_me_status), it, K_TEXT);
    copy_str(s_me_name, sizeof(s_me_name), it, K_NAME);
    update_worker();
    if (s_sos && window_stack_contains_window(s_sos) && !s_sos_holding) sos_show_state();
    menu_layer_reload_data(s_menu);
  } else if (cmd == C_ALERT) {
    char kind[16] = "", id[16] = "", name[25] = "", text[96] = "";
    copy_str(kind, sizeof(kind), it, K_KIND);
    copy_str(id, sizeof(id), it, K_ID);
    copy_str(name, sizeof(name), it, K_NAME);
    copy_str(text, sizeof(text), it, K_TEXT);
    if (strcmp(kind, "sos") == 0) {
      open_alarm(id, name, text);
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

static void say_hello(void *data) { send_cmd(C_HELLO, NULL, NULL, NULL, battery_state_service_peek().charge_percent); }

int main(void) {
  s_main = window_create();
  window_set_window_handlers(s_main, (WindowHandlers){.load = main_load, .unload = main_unload});
  window_stack_push(s_main, true);

  app_message_register_inbox_received(inbox);
  app_message_register_outbox_failed(outbox_failed);
  app_message_open(512, 256);
  battery_state_service_subscribe(battery_changed);

  // Opened by the fall-detection worker: count down before sending an SOS.
  if (launch_reason() == APP_LAUNCH_WORKER && persist_exists(P_FALL_AT)) {
    time_t at = (time_t)persist_read_int(P_FALL_AT);
    persist_delete(P_FALL_AT);
    if (time(NULL) - at < 60) open_fall_countdown();
  }
  app_timer_register(300, say_hello, NULL);

  app_event_loop();

  battery_state_service_unsubscribe();
  window_destroy(s_main);
}
