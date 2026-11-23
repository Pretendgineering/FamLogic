// famlogic-tts: keeps a Kokoro voice model (via sherpa-onnx) loaded and reads text
// aloud on request, so each message only pays for speaking, not for loading.
//
//   famlogic-tts <model-dir> <threads>
//
// Reads one request per line on stdin:  <speaker id> TAB <speed> TAB <output.wav> TAB <text>
// (speed 1.0 is normal; 0.85 is a little slower, for young listeners)
// and answers one line on stdout: "ok" or "error <reason>".
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "sherpa-onnx/c-api/c-api.h"

int main(int argc, char **argv) {
  if (argc < 3) {
    fprintf(stderr, "usage: famlogic-tts <model-dir> <threads>\n");
    return 2;
  }
  static char model[4096], voices[4096], tokens[4096], lexicon[4096], espeak[4096];
  snprintf(model, sizeof model, "%s/model.onnx", argv[1]);
  snprintf(voices, sizeof voices, "%s/voices.bin", argv[1]);
  snprintf(tokens, sizeof tokens, "%s/tokens.txt", argv[1]);
  snprintf(lexicon, sizeof lexicon, "%s/lexicon-us-en.txt", argv[1]);
  // espeak-ng's data lets it say any word, names included (a lexicon alone skips unknown words).
  snprintf(espeak, sizeof espeak, "%s/espeak-ng-data", argv[1]);

  SherpaOnnxOfflineTtsConfig config;
  memset(&config, 0, sizeof config);
  config.model.kokoro.model = model;
  config.model.kokoro.voices = voices;
  config.model.kokoro.tokens = tokens;
  config.model.kokoro.lexicon = lexicon;
  config.model.kokoro.data_dir = espeak;
  config.model.kokoro.lang = "en-us";
  config.model.kokoro.length_scale = 1.0f;
  config.model.num_threads = atoi(argv[2]) > 0 ? atoi(argv[2]) : 2;
  config.model.provider = "cpu";
  config.max_num_sentences = 1;

  const SherpaOnnxOfflineTts *tts = SherpaOnnxCreateOfflineTts(&config);
  if (!tts) {
    fprintf(stderr, "could not load the voice model from %s\n", argv[1]);
    return 1;
  }
  printf("ready %d\n", SherpaOnnxOfflineTtsNumSpeakers(tts));
  fflush(stdout);

  char *line = NULL;
  size_t cap = 0;
  ssize_t len;
  while ((len = getline(&line, &cap, stdin)) > 0) {
    if (line[len - 1] == '\n') line[--len] = 0;
    char *sid = line;
    char *speed = strchr(sid, '\t');
    if (!speed) { printf("error bad request\n"); fflush(stdout); continue; }
    *speed++ = 0;
    char *out = strchr(speed, '\t');
    if (!out) { printf("error bad request\n"); fflush(stdout); continue; }
    *out++ = 0;
    float rate = (float)atof(speed);
    if (rate < 0.5f || rate > 2.0f) rate = 1.0f;
    char *text = strchr(out, '\t');
    if (!text) { printf("error bad request\n"); fflush(stdout); continue; }
    *text++ = 0;
    const SherpaOnnxGeneratedAudio *audio = SherpaOnnxOfflineTtsGenerate(tts, text, atoi(sid), rate);
    if (!audio || audio->n == 0) {
      printf("error nothing to say\n");
    } else if (!SherpaOnnxWriteWave(audio->samples, audio->n, audio->sample_rate, out)) {
      printf("error could not write %s\n", out);
    } else {
      printf("ok\n");
    }
    if (audio) SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio);
    fflush(stdout);
  }
  free(line);
  SherpaOnnxDestroyOfflineTts(tts);
  return 0;
}
