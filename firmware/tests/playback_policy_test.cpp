#include "../arduino/AiVisionGlasses/PlaybackPolicy.h"
#include <assert.h>
#include <stdio.h>

int main() {
  using namespace glasses;
  assert(!playbackFits(0, 65536));
  assert(!playbackFits(3, 65536));
  assert(!playbackFits(PlaybackMaximumBytes + 2, PlaybackMaximumBytes + 2));
  assert(!playbackFits(96000, 65536));
  assert(playbackFits(2, 16384));
  assert(playbackFits(PlaybackMaximumBytes, PlaybackMaximumBytes));
  assert(!playbackReady(96000, 65536, false));
  assert(!playbackReady(96000, 65536, true));
  assert(!playbackReady(96000, 96000, false));
  assert(!playbackReady(96000, 96002, true));
  assert(playbackReady(2, 2, true));
  assert(playbackReady(PlaybackMaximumBytes, PlaybackMaximumBytes, true));
  assert(!playbackTransferExpired(true, false, 9999, 0));
  assert(playbackTransferExpired(true, false, 10000, 0));
  assert(!playbackTransferExpired(true, true, 30000, 0));
  assert(!playbackTransferExpired(false, false, 30000, 0));
  assert(playbackTransferExpired(true, false, uint32_t(0xfffffff0U + 10000U), 0xfffffff0U));
  puts("PASS: bounded full-response storage, no playback before all PCM and END");
}
