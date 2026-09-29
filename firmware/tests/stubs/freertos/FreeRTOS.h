#pragma once
#include <stdint.h>
#include <stdexcept>
using TaskHandle_t = void *;
constexpr int pdPASS = 1;
constexpr int pdTRUE = 1;
constexpr uint32_t portMAX_DELAY = 0xffffffffU;
namespace task_test {
struct Paused : std::exception {};
inline void (*entry)(void *) = nullptr;
inline void *argument = nullptr;
inline unsigned notifications = 0;
inline unsigned creations = 0;
// Single-step harness only: this does not simulate real FreeRTOS scheduling.
inline void step() { try { entry(argument); } catch (const Paused &) {} }
}
