#pragma once
// Nguoi 1: Parser, event — khop voi docs/protocol.md
void protocol_poll();
bool protocol_parse(const char* data, int len);
