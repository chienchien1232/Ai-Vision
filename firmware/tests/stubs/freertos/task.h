#pragma once
#include "FreeRTOS.h"
inline int xTaskCreate(void (*entry)(void *), const char *, uint32_t, void *arg, unsigned, TaskHandle_t *handle) {
  task_test::entry = entry; task_test::argument = arg; task_test::creations++;
  *handle = arg; return pdPASS;
}
inline void xTaskNotifyGive(TaskHandle_t) { task_test::notifications++; }
inline unsigned ulTaskNotifyTake(int, uint32_t) {
  if (!task_test::notifications) throw task_test::Paused{};
  unsigned count = task_test::notifications; task_test::notifications = 0; return count;
}
