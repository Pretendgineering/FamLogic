// Thereabouts fall detection (runs in the background while switched on in the
// phone app). A fall looks like a moment of free fall (almost no acceleration)
// followed shortly by a hard impact. When it sees one, it opens the Thereabouts
// app, which counts down 30 seconds before sending an SOS; any button cancels.
#include <pebble_worker.h>

#define P_FALL_AT 1

#define FREEFALL_MG 400   // below this, the watch is falling
#define FREEFALL_SAMPLES 3 // ...for at least 3 samples (120 ms at 25 Hz)
#define IMPACT_MG 2600    // then an impact above ~2.6 g
#define IMPACT_WINDOW 25  // within 1 second (25 samples)
#define COOLDOWN_S 60     // at most one alarm a minute

static int s_freefall_run;
static int s_since_freefall = -1;
static time_t s_last_alarm;

static uint32_t isqrt(uint32_t x) {
  uint32_t r = 0, b = 1u << 30;
  while (b > x) b >>= 2;
  while (b) {
    if (x >= r + b) { x -= r + b; r = (r >> 1) + b; } else { r >>= 1; }
    b >>= 2;
  }
  return r;
}

static void samples(AccelData *data, uint32_t n) {
  for (uint32_t i = 0; i < n; i++) {
    if (data[i].did_vibrate) { s_freefall_run = 0; s_since_freefall = -1; continue; }
    int32_t x = data[i].x, y = data[i].y, z = data[i].z;
    uint32_t mag = isqrt((uint32_t)(x * x + y * y + z * z));
    if (mag < FREEFALL_MG) {
      s_freefall_run++;
      if (s_freefall_run >= FREEFALL_SAMPLES) s_since_freefall = 0;
    } else {
      s_freefall_run = 0;
    }
    if (s_since_freefall >= 0) {
      s_since_freefall++;
      if (mag > IMPACT_MG) {
        s_since_freefall = -1;
        time_t now = time(NULL);
        if (now - s_last_alarm > COOLDOWN_S) {
          s_last_alarm = now;
          persist_write_int(P_FALL_AT, (int32_t)now);
          worker_launch_app();
        }
      } else if (s_since_freefall > IMPACT_WINDOW) {
        s_since_freefall = -1;
      }
    }
  }
}

int main(void) {
  accel_data_service_subscribe(10, samples);
  accel_service_set_sampling_rate(ACCEL_SAMPLING_25HZ);
  worker_event_loop();
  accel_data_service_unsubscribe();
}
