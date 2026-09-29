"""Build-only pinned TTS assets and five pre-rendered Vietnamese replies."""
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import venv
import wave
from pathlib import Path

MODEL = "vits-piper-vi_VN-vais1000-medium"
SHA256 = "fa1367710767d36ed5cf13b4a449e20c35ffd12791c2e47c2e64142bfa55551a"
PHRASES = {
    "photo_saved": "Đã chụp và lưu ảnh.",
    "video_saved": "Đã lưu video.",
    "greeting": "Xin chào. Bạn cần mình giúp gì?",
    "help": "Bạn có thể chụp ảnh, quay hoặc dừng video, kiểm tra kính, hỏi giờ và tính toán. Câu hỏi khác có thể dùng AI.",
    "repeat": "Không nghe rõ. Hãy nói lại.",
}


def digest(path):
    sha = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            sha.update(block)
    return sha.hexdigest()


def prepare(output, cache):
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / (MODEL + ".tar.bz2")
    if not archive.is_file() or digest(archive) != SHA256:
        print("Downloading pinned Vietnamese TTS model", flush=True)
        urllib.request.urlretrieve(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" + archive.name, archive)
    if digest(archive) != SHA256:
        raise RuntimeError("Vietnamese TTS checksum mismatch")
    root = cache / MODEL
    with tarfile.open(archive) as tar:
        for member in tar:
            target = (cache / member.name).resolve()
            if not target.is_relative_to(root.resolve()) or not (member.isdir() or member.isfile()):
                raise RuntimeError("Unsafe model archive entry")
            tar.extract(member, cache)
    model_dir = output / "tts/vi"
    model_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(root / "vi_VN-vais1000-medium.onnx", model_dir / "model.onnx")
    shutil.copy2(root / "tokens.txt", model_dir / "tokens.txt")
    shutil.copytree(root / "espeak-ng-data", model_dir / "espeak-ng-data", dirs_exist_ok=True)
    for name in ("MODEL_CARD", "LICENSE", "README.md"):
        if (root / name).is_file():
            shutil.copy2(root / name, model_dir / name)
    shutil.copy2(Path(__file__).with_name("vi-tts-notices.txt"), model_dir / "NOTICE.txt")
    # These are upstream license artifacts, not application source files.
    for name, url in {
        "ESPEAK-COPYING.txt": "https://raw.githubusercontent.com/espeak-ng/espeak-ng/1.52.0/COPYING",
        "SHERPA-LICENSE.txt": "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/LICENSE",
        "PIPER-LICENSE.txt": "https://raw.githubusercontent.com/rhasspy/piper/master/LICENSE.md",
    }.items():
        cached = cache / name
        if not cached.is_file():
            urllib.request.urlretrieve(url, cached)
        shutil.copy2(cached, model_dir / name)
    files = [{"path": str(p.relative_to(model_dir)).replace("\\", "/"), "size": p.stat().st_size,
              "sha256": digest(p)} for p in sorted(model_dir.rglob("*"))
             if p.is_file() and p.name != "manifest.json"]
    (model_dir / "manifest.json").write_text(json.dumps({"archiveSha256": SHA256, "files": files}), encoding="utf-8")
    return root


def render(output, root):
    import numpy as np
    import sherpa_onnx
    config = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(root / "vi_VN-vais1000-medium.onnx"),
            tokens=str(root / "tokens.txt"), data_dir=str(root / "espeak-ng-data")),
        num_threads=2, provider="cpu"), max_num_sentences=1)
    if not config.validate():
        raise RuntimeError("Invalid pinned TTS model")
    tts = sherpa_onnx.OfflineTts(config)
    audio_dir = output / "tts/replies"
    audio_dir.mkdir(parents=True, exist_ok=True)
    for key, text in PHRASES.items():
        audio = tts.generate(text, sid=0, speed=1.0)
        samples = np.asarray(audio.samples)
        count = len(samples) * 16000 // audio.sample_rate
        if not 1 <= count <= 16000 * 30:
            raise RuntimeError("Preset exceeds playback limit: " + key)
        positions = np.arange(count) * len(samples) / count
        mono = np.interp(positions, np.arange(len(samples)), samples)
        pcm = (np.clip(mono, -1, 1) * 32767).astype("<i2")
        (audio_dir / (key + ".pcm")).write_bytes(pcm.tobytes())
        preview_dir = output.parent / "ttsPreview"
        preview_dir.mkdir(parents=True, exist_ok=True)
        with wave.open(str(preview_dir / (key + ".wav")), "wb") as preview:
            preview.setnchannels(1)
            preview.setsampwidth(2)
            preview.setframerate(16000)
            preview.writeframes(pcm.tobytes())
        print(f"Preset {key}: {count / 16000:.2f}s", flush=True)
    (audio_dir / "phrases.json").write_text(json.dumps(PHRASES, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--render", action="store_true")
    args = parser.parse_args()
    if args.render:
        render(args.output, args.cache / MODEL)
    else:
        root = prepare(args.output, args.cache)
        env = args.cache / "python-env"
        python = env / ("Scripts/python.exe" if sys.platform == "win32" else "bin/python")
        if not python.is_file():
            venv.create(env, with_pip=True)
        probe = subprocess.run([str(python), "-c", "import sherpa_onnx,numpy; assert sherpa_onnx.__version__=='1.13.8'"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if probe.returncode:
            subprocess.run([str(python), "-m", "pip", "install", "sherpa-onnx==1.13.8", "numpy==2.2.6"], check=True)
        subprocess.run([str(python), str(Path(__file__).resolve()), "--render", "--output", str(args.output),
                        "--cache", str(args.cache)], check=True)
