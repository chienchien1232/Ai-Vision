"""Use the canonical render_docx rasterizer with an already-exported Word PDF.

Windows has no bundled LibreOffice. The native Word PDF is rendered using the
bundled Poppler backend; the managed skill package is never edited.
"""
import importlib.util
import os
from pathlib import Path
import shutil
import sys

root = Path(__file__).resolve().parents[2]
skill = Path("C:/Users/Admin/.codex/plugins/cache/openai-primary-runtime/documents/26.909.12148/skills/documents/render_docx.py")
pdf = root / "build/document-qa/four-feature-plan.pdf"
spec = importlib.util.spec_from_file_location("canonical_docx_renderer", skill)
renderer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(renderer)


def word_pdf(doc_path, user_profile, convert_dir, stem, verbose=False):
    expected = root / "docs/Ke_hoach_hoan_thien_4_chuc_nang_kinh_AI_28-09-2026.docx"
    if Path(doc_path).resolve() != expected.resolve() or not pdf.is_file():
        raise ValueError("Only the verified project plan and its native Word PDF are allowed")
    if pdf.stat().st_mtime < expected.stat().st_mtime:
        raise ValueError("Re-export Word PDF after changing the DOCX")
    target = Path(convert_dir) / f"{stem}.pdf"
    shutil.copy2(pdf, target)
    return str(target), "Native Word PDF export"


renderer.convert_to_pdf = word_pdf
os.environ["PATH"] = "C:/Users/Admin/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/poppler/Library/bin" + os.pathsep + os.environ["PATH"]
sys.argv = [str(skill), str(root / "docs/Ke_hoach_hoan_thien_4_chuc_nang_kinh_AI_28-09-2026.docx"),
            "--output_dir", str(root / "build/document-qa/pages"), "--dpi", "130"]
renderer.main()
