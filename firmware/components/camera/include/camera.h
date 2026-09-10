#pragma once
// Nguoi 2: Chup, frame buffer
bool camera_capture(uint8_t** out_buf, int* out_len);
void camera_free(uint8_t* buf);
