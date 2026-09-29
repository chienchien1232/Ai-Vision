"""Small private backend: API keys are environment variables, never APK assets."""

import base64
import hmac
import json
import os
import io
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
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
    candidates = data.get("candidates") or []
    parts = candidates[0].get("content", {}).get("parts", []) if candidates else []
    answer = "".join(part.get("text", "") for part in parts if isinstance(part, dict)).strip()
    if not answer:
        raise RuntimeError("AI returned no text")
    return answer


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
    candidates = data.get("candidates") or []
    parts = candidates[0].get("content", {}).get("parts", []) if candidates else []
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
    """Normalize cloud TTS output to PCM_S16LE mono 16 kHz for the glasses speaker."""
    try:
        import audioop  # Legacy opt-in audio routes only; Python <= 3.12.
    except ImportError as error:
        raise ValueError("Legacy cloud audio needs Python <= 3.12; use local Android STT/TTS") from error
    if mime.startswith("audio/wav") or raw[:4] == b"RIFF":
        with wave.open(io.BytesIO(raw), "rb") as wav:
            if wav.getcomptype() != "NONE":
                raise ValueError("Unsupported TTS audio")
            channels, width, rate = wav.getnchannels(), wav.getsampwidth(), wav.getframerate()
            if channels not in (1, 2) or width not in (1, 2) or not 4000 <= rate <= 96000:
                raise ValueError("Unsupported TTS audio")
            frames = wav.readframes(wav.getnframes())
        if not frames:
            raise RuntimeError("AI returned no audio")
        if width == 1:  # unsigned 8-bit -> signed 16-bit
            frames = audioop.lin2lin(audioop.bias(frames, 1, -128), 1, 2)
        if channels == 2:
            frames = audioop.tomono(frames, 2, 0.5, 0.5)
        if rate != 16000:
            frames, _ = audioop.ratecv(frames, 2, 1, rate, 16000, None)
    else:  # raw L16 PCM, e.g. audio/L16;rate=24000
        rate = 24000
        for token in mime.split(";"):
            token = token.strip()
            if token.startswith("rate=") and token[5:].isdigit():
                rate = int(token[5:])
        if not raw or len(raw) % 2 != 0 or not 4000 <= rate <= 96000:
            raise ValueError("Unsupported TTS audio")
        frames = raw
        if rate != 16000:
            frames, _ = audioop.ratecv(frames, 2, 1, rate, 16000, None)
    if len(frames) > MAX_SPEAK_BYTES:
        raise ValueError("Answer audio is longer than 30 seconds")
    return frames


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
            self.send_json(*upstream_error(error.code))
            return
        except (URLError, TimeoutError):
            self.send_json(504, {"error": "AI_TIMEOUT"})
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
            self.send_json(*upstream_error(error.code))
            return
        except (URLError, TimeoutError):
            self.send_json(504, {"error": "AI_TIMEOUT"})
            return
        except RuntimeError:
            self.send_json(502, {"error": "AI_EMPTY_RESPONSE"})
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
