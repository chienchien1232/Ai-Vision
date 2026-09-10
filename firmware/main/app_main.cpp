#include "core.h"
#include "connectivity.h"
#include "protocol.h"
#include "board.h"

// Nguoi 1 tich hop: boot -> board init -> connectivity -> loop event
extern "C" void app_main(void) {
    core_boot();
    board_init();
    connectivity_init();
    while (true) {
        protocol_poll();
    }
}
