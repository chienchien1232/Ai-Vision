"""Small private backend: API keys are environment variables, never APK assets."""

import base64
import hmac
import json
import os
import io
import struct
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen

MODEL = os.getenv("GEMINI_MODEL", "gemini-3.8-flash")
MAX_IMAGE_BYTES = 5 * 1024 * 1024
MAX_AUDIO_BYTES = 1024 * 1024
TRANSCRIBE_MODEL = os.getenv("GEMINI_TRANSCRIBE_MODEL", MODEL)
TTS_MODEL = os.getenv("GEMINI_TTS_MODEL", "gemini-3.8-flash-tts")
TTS_VOICE = os.getenv("GEMINI_TTS_VOICE", "Kore")
MAX_SPEAK_BYTES = 16000 * 2 * 30  # 30 s of PCM_S16LE mono 16 kHz


def upstream_error(code: int) -> tuple[int, dict]:
    """Distinguish server-key rejection from a bad app token; never forward upstream bodies."""
    kind = "AI_QUOTA" if code == 429 else "AI_AUTH_FAILED" if code in (401, 403) else "AI_HTTP_ERROR"
    return (429 if code == 429 else 502), {"error": kind, "status": code}


def network_error(error: OSError) -> tuple[int, dict]:
    timeout = isinstance(error, TimeoutError) or isinstance(getattr(error, "reason", None), TimeoutError)
    return (504, {"error": "AI_TIMEOUT"}) if timeout else (502, {"error": "AI_UNAVAILABLE"})


def make_payload(question: str, image_base64: str | None) -> dict:
    if not question.strip() or len(question) > 4000:
        raise ValueError("Question must contain 1..4000 characters")
    parts = [{"text": question.strip()}]
    if image_base64 is not None:
        try:
            image = base64.b64decode(image_base64, validate=True)
        except (ValueError, base64.binascii.Error) as error:
            raise ValueError("Invalid imageBase64") from error
        if not 4 <= len(image) <= MAX_IMAGE_BYTES or not image.startswith(b"\xff\xd8") or not image.endswith(b"\xff\xd9"):
            raise ValueError("Invalid JPEG image")
        parts.insert(0, {"inline_data": {"mime_type": "image/jpeg", "data": image_base64}})
        system_text = "Answer in at most 3 short sentences, under 90 words, in the user's language. Describe only what is visible in the supplied image; state uncertainty when needed."
    else:
        system_text = "Answer in at most 3 short sentences, under 90 words, in the user's language. State uncertainty when needed."
    return {
        "system_instruction": {"parts": [{"text": system_text}]},
        "contents": [{"parts": parts}],
        "generationConfig": {"thinkingConfig": {"thinkingLevel": "low"}, "maxOutputTokens": 512},
    }


def make_transcription_payload(audio_base64: str, language_tag: str) -> dict:
    if language_tag not in ("vi-VN", "en-US"):
        raise ValueError("Unsupported languageTag")
    try:
        audio = base64.b64decode(audio_base64, validate=True)
    except (ValueError, base64.binascii.Error) as error:
        raise ValueError("Invalid audioBase64") from error
    if not 44 <= len(audio) <= MAX_AUDIO_BYTES:
        raise ValueError("Invalid audio size")
    try:
        with wave.open(io.BytesIO(audio), "rb") as wav:
            if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getcomptype()) != (1, 2, 16000, "NONE"):
                raise ValueError("Expected PCM16 mono 16 kHz WAV")
            if not 1 <= wav.getnframes() <= 16000 * 30:
                raise ValueError("Audio must be 0..30 seconds")
            if len(wav.readframes(wav.getnframes())) != wav.getnframes() * 2:
                raise ValueError("Truncated WAV audio")
    except (wave.Error, EOFError) as error:
        raise ValueError("Invalid WAV audio") from error
    return {
        "contents": [{"parts": [
            {"text": f"Transcribe the speech in this audio in {language_tag}. Return only the spoken words, with no explanation. If no speech is intelligible, return an empty response."},
            {"inline_data": {"mime_type": "audio/wav", "data": audio_base64}},
        ]}],
        "generationConfig": {"thinkingConfig": {"thinkingLevel": "low"}},
    }


def ask_gemini(payload: dict, api_key: str, model: str = MODEL) -> str:
    request = Request(
        f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "x-goog-api-key": api_key},
        method="POST",
    )
    with urlopen(request, timeout=35) as response:
        data = json.load(response)
    parts = response_parts(data)
    answer = "".join(part["text"] for part in parts
                     if not part.get("thought") and isinstance(part.get("text"), str)).strip()
    if not answer:
        raise RuntimeError("AI returned no text")
    return answer


def response_parts(data: object) -> list[dict]:
    """Validate the upstream shape before extracting text/audio, without leaking its body."""
    if not isinstance(data, dict):
        raise ValueError("Invalid AI response")
    candidates = data.get("candidates", [])
    if not isinstance(candidates, list):
        raise ValueError("Invalid AI candidates")
    if not candidates:
        raise RuntimeError("AI returned no candidates")
    first = candidates[0]
    if not isinstance(first, dict) or not isinstance(first.get("content", {}), dict):
        raise ValueError("Invalid AI content")
    parts = first.get("content", {}).get("parts", [])
    if not isinstance(parts, list) or any(not isinstance(part, dict) for part in parts):
        raise ValueError("Invalid AI parts")
    return parts


def make_speech_payload(text: str, language_tag: str) -> dict:
    if language_tag not in ("vi-VN", "en-US"):
        raise ValueError("Unsupported languageTag")
    if not text.strip() or len(text) > 4000:
        raise ValueError("Text must contain 1..4000 characters")
    voice_language = "Vietnamese" if language_tag == "vi-VN" else "American English"
    return {
        "contents": [{"parts": [{"text": f"Speak in {voice_language} with a natural voice: {text.strip()}"}]}],
        "generationConfig": {
            "responseModalities": ["AUDIO"],
            "speechConfig": {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": TTS_VOICE}}},
        },
    }


def ask_gemini_audio(payload: dict, api_key: str, model: str = TTS_MODEL) -> tuple[bytes, str]:
    request = Request(
        f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "x-goog-api-key": api_key},
        method="POST",
    )
    with urlopen(request, timeout=60) as response:
        data = json.load(response)
    parts = response_parts(data)
    for part in parts:
        if not isinstance(part, dict):
            continue
        blob = part.get("inlineData") or part.get("inline_data")
        if not isinstance(blob, dict) or not isinstance(blob.get("data"), str):
            continue
        try:
            raw = base64.b64decode(blob["data"], validate=True)
        except (ValueError, base64.binascii.Error) as error:
            raise ValueError("Invalid TTS audio") from error
        if not raw:
            continue
        mime = str(blob.get("mimeType") or blob.get("mime_type") or "")
        return raw, mime
    raise RuntimeError("AI returned no audio")


def to_pcm16k_mono(raw: bytes, mime: str) -> bytes:
    """Normalize legacy TTS without audioop (removed in Python 3.13).

    Use the same bounded linear interpolation as the Android renderer. Raw Gemini
    L16 is interpreted as little-endian mono, matching the existing API contract.
    """
    if mime.startswith("audio/wav") or raw[:4] == b"RIFF":
        try:
            with wave.open(io.BytesIO(raw), "rb") as wav:
                channels, width, rate = wav.getnchannels(), wav.getsampwidth(), wav.getframerate()
                count = wav.getnframes()
                if wav.getcomptype() != "NONE" or channels not in (1, 2) or width not in (1, 2) or not 4000 <= rate <= 96000:
                    raise ValueError("Unsupported TTS audio")
                if count > rate * 30:
                    raise ValueError("Answer audio is longer than 30 seconds")
                frames = wav.readframes(count)
                if len(frames) != count * channels * width:
                    raise ValueError("Truncated WAV audio")
        except (wave.Error, EOFError) as error:
            raise ValueError("Invalid WAV audio") from error
    else:
        if mime.split(";", 1)[0].strip().lower() != "audio/l16":
            raise ValueError("Unsupported TTS audio")
        channels, width = 1, 2
        rate = 24000
        for token in mime.split(";")[1:]:
            token = token.strip()
            if token.startswith("rate="):
                if not token[5:].isdigit():
                    raise ValueError("Invalid TTS sample rate")
                rate = int(token[5:])
            elif token.startswith("channels=") and token != "channels=1":
                raise ValueError("Unsupported TTS channels")
        if len(raw) % 2 != 0 or not 4000 <= rate <= 96000:
            raise ValueError("Unsupported TTS audio")
        frames = raw
        if len(frames) // 2 > rate * 30:
            raise ValueError("Answer audio is longer than 30 seconds")
    if not frames:
        raise RuntimeError("AI returned no audio")
    samples = [(value - 128) * 256 for value in frames] if width == 1 else [value[0] for value in struct.iter_unpack("<h", frames)]
    if channels == 2:
        samples = [int((samples[i] + samples[i + 1]) / 2) for i in range(0, len(samples), 2)]
    if rate == 16000:
        return b"".join(struct.pack("<h", value) for value in samples)
    count = max(1, len(samples) * 16000 // rate)
    output = bytearray(count * 2)
    for index in range(count):
        left, fraction = divmod(index * rate, 16000)
        left = min(left, len(samples) - 1)
        right = min(left + 1, len(samples) - 1)
        value = round((samples[left] * (16000 - fraction) + samples[right] * fraction) / 16000)
        struct.pack_into("<h", output, index * 2, value)
    return bytes(output)


class Handler(BaseHTTPRequestHandler):
    def send_json(self, code: int, data: dict) -> None:
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        if self.path == "/health":
            self.send_json(200, {"ok": True})
        else:
            self.send_json(404, {"error": "NOT_FOUND"})

    def do_POST(self) -> None:
        if self.path not in ("/v1/answer", "/v1/transcribe", "/v1/speak"):
            self.send_json(404, {"error": "NOT_FOUND"})
            return
        if self.path != "/v1/answer" and os.getenv("ENABLE_LEGACY_CLOUD_AUDIO") != "1":
            self.send_json(410, {"error": "LOCAL_AUDIO_ONLY"})
            return
        expected = os.environ["APP_TOKEN"]
        if not hmac.compare_digest(self.headers.get("Authorization", ""), f"Bearer {expected}"):
            self.send_json(401, {"error": "UNAUTHORIZED"})
            return
        try:
            size = int(self.headers.get("Content-Length", "-1"))
            limit = 8 * 1024 * 1024 if self.path == "/v1/answer" else 2 * 1024 * 1024
            if self.path == "/v1/speak":
                limit = 64 * 1024
            if not 1 <= size <= limit:
                raise ValueError("Invalid request size")
            body = json.loads(self.rfile.read(size))
            if not isinstance(body, dict):
                raise ValueError("Request must be a JSON object")
            session_id = body.get("sessionId")
            if not isinstance(session_id, str) or not 1 <= len(session_id) <= 100:
                raise ValueError("Invalid sessionId")
            if self.path == "/v1/answer":
                question, image_base64 = body.get("question"), body.get("imageBase64")
                if not isinstance(question, str) or (image_base64 is not None and not isinstance(image_base64, str)):
                    raise ValueError("Invalid question or imageBase64")
                payload = make_payload(question, image_base64)
            elif self.path == "/v1/transcribe":
                audio_base64, language_tag = body.get("audioBase64"), body.get("languageTag")
                if not isinstance(audio_base64, str) or not isinstance(language_tag, str):
                    raise ValueError("Invalid audioBase64 or languageTag")
                payload = make_transcription_payload(audio_base64, language_tag)
            else:
                text, language_tag = body.get("text"), body.get("languageTag")
                if not isinstance(text, str) or not isinstance(language_tag, str):
                    raise ValueError("Invalid text or languageTag")
                payload = make_speech_payload(text, language_tag)
        except (ValueError, AttributeError) as error:
            self.send_json(400, {"error": "BAD_REQUEST", "message": str(error)})
            return
        try:
            if self.path == "/v1/speak":
                raw, mime = ask_gemini_audio(payload, os.environ["GEMINI_API_KEY"], TTS_MODEL)
                pcm = to_pcm16k_mono(raw, mime)
        except HTTPError as error:
            status = error.code
            error.close()
            self.send_json(*upstream_error(status))
            return
        except OSError as error:
            self.send_json(*network_error(error))
            return
        except RuntimeError:
            self.send_json(502, {"error": "AI_EMPTY_RESPONSE"})
            return
        except ValueError as error:
            self.send_json(502, {"error": "AI_BAD_AUDIO", "message": str(error)})
            return
        if self.path == "/v1/speak":
            self.send_json(200, {
                "sessionId": session_id,
                "audioBase64": base64.b64encode(pcm).decode(),
                "sampleRate": 16000,
            })
            return
        try:
            model = MODEL if self.path == "/v1/answer" else TRANSCRIBE_MODEL
            answer = ask_gemini(payload, os.environ["GEMINI_API_KEY"], model)
        except HTTPError as error:
            status = error.code
            error.close()
            self.send_json(*upstream_error(status))
            return
        except OSError as error:
            self.send_json(*network_error(error))
            return
        except RuntimeError:
            self.send_json(502, {"error": "AI_EMPTY_RESPONSE"})
            return
        except ValueError:
            self.send_json(502, {"error": "AI_BAD_RESPONSE"})
            return
        field = "answer" if self.path == "/v1/answer" else "transcript"
        self.send_json(200, {"sessionId": session_id, field: answer})


if __name__ == "__main__":
    if not os.getenv("GEMINI_API_KEY") or not os.getenv("APP_TOKEN"):
        raise SystemExit("Set GEMINI_API_KEY and APP_TOKEN in the environment")
    host = os.getenv("BACKEND_HOST", "127.0.0.1")
    port = int(os.getenv("BACKEND_PORT", "8080"))
    print(f"Ai-Vision backend listening on {host}:{port}")
    ThreadingHTTPServer((host, port), Handler).serve_forever()
