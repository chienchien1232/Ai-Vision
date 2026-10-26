import base64
import json
import os
import io
import struct
import threading
import unittest
import wave
from http.server import ThreadingHTTPServer
from unittest.mock import patch
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

import server


class PayloadTest(unittest.TestCase):
    def test_image_question_contains_new_jpeg_and_low_thinking(self):
        jpeg = base64.b64encode(b"\xff\xd8\xff\xd9").decode()
        payload = server.make_payload("What is in front of me?", jpeg)
        self.assertEqual("image/jpeg", payload["contents"][0]["parts"][0]["inline_data"]["mime_type"])
        self.assertEqual("low", payload["generationConfig"]["thinkingConfig"]["thinkingLevel"])

    def test_invalid_image_is_rejected(self):
        with self.assertRaises(ValueError):
            server.make_payload("What?", base64.b64encode(b"not jpeg").decode())

    def test_transcription_requires_pcm16_mono_16khz(self):
        output = io.BytesIO()
        with wave.open(output, "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(16000)
            wav.writeframes(b"\x00\x00" * 1600)
        encoded = base64.b64encode(output.getvalue()).decode()
        payload = server.make_transcription_payload(encoded, "vi-VN")
        self.assertEqual("audio/wav", payload["contents"][0]["parts"][1]["inline_data"]["mime_type"])
        with self.assertRaises(ValueError):
            server.make_transcription_payload(encoded, "invalid")

    def test_truncated_transcription_is_rejected(self):
        output = io.BytesIO()
        with wave.open(output, "wb") as wav:
            wav.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
            wav.writeframes(b"\x00\x10" * 100)
        with self.assertRaises(ValueError):
            server.make_transcription_payload(base64.b64encode(output.getvalue()[:-2]).decode(), "vi-VN")


class UpstreamResponseTest(unittest.TestCase):
    def test_only_public_text_is_returned(self):
        data = {"candidates": [{"content": {"parts": [
            {"thought": True, "text": "internal reasoning"}, {"text": "Xin chào"}, {"text": 123}
        ]}}]}
        with patch.object(server, "urlopen", return_value=io.BytesIO(json.dumps(data).encode())):
            self.assertEqual("Xin chào", server.ask_gemini({}, "test-only"))

    def test_invalid_upstream_shapes_are_rejected(self):
        for data in ([], {"candidates": {}}, {"candidates": [None]},
                     {"candidates": [{"content": []}]}, {"candidates": [{"content": {"parts": "wrong"}}]},
                     {"candidates": [{"content": {"parts": [None]}}]}):
            with self.subTest(data=data), self.assertRaises(ValueError):
                server.response_parts(data)
        with self.assertRaises(RuntimeError):
            server.response_parts({"candidates": []})


class EndpointTest(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, {"APP_TOKEN": "test-token", "GEMINI_API_KEY": "unused"})
        self.env.start()
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        self.worker = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.worker.start()
        self.url = f"http://127.0.0.1:{self.httpd.server_port}/v1/answer"

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.worker.join()
        self.env.stop()

    def test_auth_and_session_echo(self):
        body = json.dumps({"sessionId": "session-1", "question": "Hello"}).encode()
        unauthenticated = Request(self.url, data=body, headers={"Content-Type": "application/json"}, method="POST")
        with self.assertRaises(HTTPError) as error:
            urlopen(unauthenticated)
        self.assertEqual(401, error.exception.code)
        error.exception.close()
        authenticated = Request(
            self.url, data=body,
            headers={"Content-Type": "application/json", "Authorization": "Bearer test-token"},
            method="POST",
        )
        with patch.object(server, "ask_gemini", return_value="Xin chào"):
            with urlopen(authenticated) as response:
                result = json.load(response)
        self.assertEqual({"sessionId": "session-1", "answer": "Xin chào"}, result)

    def test_transcription_endpoint_echoes_session(self):
        output = io.BytesIO()
        with wave.open(output, "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(16000)
            wav.writeframes(b"\x00\x00" * 1600)
        body = json.dumps({
            "sessionId": "voice-1", "languageTag": "vi-VN",
            "audioBase64": base64.b64encode(output.getvalue()).decode(),
        }).encode()
        authenticated = Request(
            self.url.replace("/v1/answer", "/v1/transcribe"), data=body,
            headers={"Content-Type": "application/json", "Authorization": "Bearer test-token"},
            method="POST",
        )
        with patch.dict(os.environ, {"ENABLE_LEGACY_CLOUD_AUDIO": "1"}), patch.object(server, "ask_gemini", return_value="Trước mặt tôi có gì?"):
            with urlopen(authenticated) as response:
                result = json.load(response)
        self.assertEqual({"sessionId": "voice-1", "transcript": "Trước mặt tôi có gì?"}, result)

    def test_legacy_audio_is_disabled_without_any_upstream_call(self):
        with patch.dict(os.environ, {"ENABLE_LEGACY_CLOUD_AUDIO": "0"}), patch.object(server, "ask_gemini") as ai:
            for route in ("transcribe", "speak"):
                request = Request(self.url.replace("answer", route), data=b"{}", method="POST")
                with self.assertRaises(HTTPError) as error:
                    urlopen(request)
                self.assertEqual(410, error.exception.code)
                error.exception.close()
            ai.assert_not_called()

    def test_quota_response_is_visible_without_auto_retry(self):
        body = json.dumps({"sessionId": "quota-1", "question": "Why is the sky blue?"}).encode()
        request = Request(self.url, data=body, headers={"Authorization": "Bearer test-token"}, method="POST")
        with patch.object(server, "ask_gemini", side_effect=HTTPError("upstream", 429, "quota", {}, None)) as ai:
            with self.assertRaises(HTTPError) as error:
                urlopen(request)
            self.assertEqual(429, error.exception.code)
            self.assertEqual("AI_QUOTA", json.load(error.exception)["error"])
            error.exception.close()
            ai.assert_called_once()

    def test_upstream_auth_failure_is_not_an_app_token_failure(self):
        body = json.dumps({"sessionId": "auth-1", "question": "Why is the sky blue?"}).encode()
        request = Request(self.url, data=body, headers={"Authorization": "Bearer test-token"}, method="POST")
        with patch.object(server, "ask_gemini", side_effect=HTTPError("upstream", 401, "unauthenticated", {}, None)) as ai:
            with self.assertRaises(HTTPError) as error:
                urlopen(request)
            self.assertEqual(502, error.exception.code)
            self.assertEqual({"error": "AI_AUTH_FAILED", "status": 401}, json.load(error.exception))
            error.exception.close()
            ai.assert_called_once()

    def test_bad_request_never_calls_ai(self):
        with patch.object(server, "ask_gemini") as ai:
            for value in ([], None, {"sessionId": "s", "question": 123}, {"sessionId": "s", "question": " "}):
                request = Request(self.url, data=json.dumps(value).encode(),
                                  headers={"Authorization": "Bearer test-token"}, method="POST")
                with self.subTest(value=value), self.assertRaises(HTTPError) as error:
                    urlopen(request)
                self.assertEqual(400, error.exception.code)
                error.exception.close()
            ai.assert_not_called()

    def test_upstream_failures_return_sanitized_errors_without_retry(self):
        for failure, status, code in (
            (ValueError("private upstream body"), 502, "AI_BAD_RESPONSE"),
            (RuntimeError("empty"), 502, "AI_EMPTY_RESPONSE"),
            (TimeoutError("private address"), 504, "AI_TIMEOUT"),
            (URLError(TimeoutError()), 504, "AI_TIMEOUT"),
            (URLError("private DNS failure"), 502, "AI_UNAVAILABLE"),
            (ConnectionResetError("private upstream address"), 502, "AI_UNAVAILABLE"),
        ):
            request = Request(self.url, data=json.dumps({"sessionId": "s", "question": "Hello"}).encode(),
                              headers={"Authorization": "Bearer test-token"}, method="POST")
            with self.subTest(code=code), patch.object(server, "ask_gemini", side_effect=failure) as ai:
                with self.assertRaises(HTTPError) as error:
                    urlopen(request)
                self.assertEqual(status, error.exception.code)
                self.assertEqual({"error": code}, json.load(error.exception))
                error.exception.close()
                ai.assert_called_once()


class SpeakTest(unittest.TestCase):
    def test_speech_payload_targets_vietnamese_voice(self):
        payload = server.make_speech_payload("Xin chào", "vi-VN")
        self.assertIn("AUDIO", payload["generationConfig"]["responseModalities"])
        self.assertIn("Vietnamese", payload["contents"][0]["parts"][0]["text"])
        with self.assertRaises(ValueError):
            server.make_speech_payload("Xin chào", "invalid")
        with self.assertRaises(ValueError):
            server.make_speech_payload("   ", "vi-VN")

    def test_wav_normalizes_to_pcm16k_mono(self):
        output = io.BytesIO()
        with wave.open(output, "wb") as wav:
            wav.setnchannels(2)
            wav.setsampwidth(2)
            wav.setframerate(24000)
            wav.writeframes(b"\x00\x10\x00\x10" * 2400)  # 0.1 s stereo 24 kHz
        pcm = server.to_pcm16k_mono(output.getvalue(), "audio/wav")
        self.assertEqual(16000 * 2 // 10, len(pcm))

    def test_rejects_oversize_speech_audio(self):
        with self.assertRaises(ValueError):
            server.to_pcm16k_mono(b"\x00\x00" * (server.MAX_SPEAK_BYTES // 2 + 1), "audio/L16;rate=16000")

    def test_mono_pcm16_is_bit_exact(self):
        pcm = struct.pack("<5h", -32768, -1, 0, 1, 32767)
        self.assertEqual(pcm, server.to_pcm16k_mono(pcm, "audio/L16;rate=16000"))

    def test_unsigned_wav_and_stereo_mix_keep_signed_amplitudes(self):
        output = io.BytesIO()
        with wave.open(output, "wb") as wav:
            wav.setparams((1, 1, 16000, 0, "NONE", "not compressed"))
            wav.writeframes(bytes([0, 128, 255]))
        self.assertEqual((-32768, 0, 32512), struct.unpack("<3h", server.to_pcm16k_mono(output.getvalue(), "audio/wav")))
        output = io.BytesIO()
        with wave.open(output, "wb") as wav:
            wav.setparams((2, 2, 16000, 0, "NONE", "not compressed"))
            wav.writeframes(struct.pack("<4h", -2000, 2000, 1000, 3000))
        self.assertEqual((0, 2000), struct.unpack("<2h", server.to_pcm16k_mono(output.getvalue(), "audio/wav")))

    def test_resampling_interpolates_and_preserves_duration(self):
        pcm = server.to_pcm16k_mono(struct.pack("<2h", 0, 1000), "audio/L16;rate=8000")
        self.assertEqual((0, 500, 1000, 1000), struct.unpack("<4h", pcm))

    def test_invalid_format_empty_and_truncated_wav_fail(self):
        for mime, pcm in (("audio/mpeg", b"\x00\x00"), ("audio/L16;rate=invalid", b"\x00\x00"),
                          ("audio/L16;rate=16000;channels=2", b"\x00\x00"), ("audio/L16", b"\x00"),
                          ("audio/wav", b"RIFF")):
            with self.subTest(mime=mime), self.assertRaises(ValueError):
                server.to_pcm16k_mono(pcm, mime)
        with self.assertRaises(RuntimeError):
            server.to_pcm16k_mono(b"", "audio/L16")
        output = io.BytesIO()
        with wave.open(output, "wb") as wav:
            wav.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
            wav.writeframes(b"\x00\x00" * 100)
        with self.assertRaises(ValueError):
            server.to_pcm16k_mono(output.getvalue()[:-2], "audio/wav")


class SpeakEndpointTest(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, {"APP_TOKEN": "test-token", "GEMINI_API_KEY": "unused", "ENABLE_LEGACY_CLOUD_AUDIO": "1"})
        self.env.start()
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        self.worker = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.worker.start()
        self.url = f"http://127.0.0.1:{self.httpd.server_port}/v1/speak"

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.worker.join()
        self.env.stop()

    def test_speak_returns_session_pcm(self):
        pcm = b"\x01\x02" * 8000  # 1 s of PCM_S16LE mono 16 kHz
        body = json.dumps({"sessionId": "voice-9", "text": "Xin chào", "languageTag": "vi-VN"}).encode()
        unauthenticated = Request(self.url, data=body, headers={"Content-Type": "application/json"}, method="POST")
        with self.assertRaises(HTTPError) as error:
            urlopen(unauthenticated)
        self.assertEqual(401, error.exception.code)
        error.exception.close()
        authenticated = Request(
            self.url, data=body,
            headers={"Content-Type": "application/json", "Authorization": "Bearer test-token"},
            method="POST",
        )
        with patch.object(server, "ask_gemini_audio", return_value=(pcm, "audio/L16;rate=16000")):
            with urlopen(authenticated) as response:
                result = json.load(response)
        self.assertEqual("voice-9", result["sessionId"])
        self.assertEqual(16000, result["sampleRate"])
        self.assertEqual(pcm, base64.b64decode(result["audioBase64"]))


if __name__ == "__main__":
    unittest.main()
