#!/usr/bin/env python3
"""Build docs/privacy.html (the page served at https://riyadmondol2006.github.io/WakeNudge/privacy) from PRIVACY.md.

    python3 -I scripts/build-privacy-page.py            # rewrite docs/privacy.html
    python3 -I scripts/build-privacy-page.py --check    # exit 1 if docs/privacy.html is out of date (for CI)

Standard library only. The output is a pure function of PRIVACY.md and the <style> block of docs/index.html, so running
it twice gives byte-identical files and nothing in it depends on the date. Re-run it whenever PRIVACY.md changes.

Supported Markdown subset (all PRIVACY.md needs): ATX headings, paragraphs, bullet and numbered lists (with indented
continuation lines), pipe tables, block quotes, fenced code, horizontal rules, and inline code, bold, italic and links.
Relative links are rewritten to the matching file on GitHub (blob/main/...), because the page lives in docs/ and
the files they point at do not.
"""
from __future__ import annotations

import html
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "PRIVACY.md"
INDEX = ROOT / "docs" / "index.html"
TARGET = ROOT / "docs" / "privacy.html"

SITE = "https://riyadmondol2006.github.io/WakeNudge"
REPO = "https://github.com/riyadmondol2006/WakeNudge"
CANONICAL = f"{SITE}/privacy"
OG_IMAGE = f"{SITE}/assets/og-image.png"

# --------------------------------------------------------------------------------------------------------------------
# Inline Markdown
# --------------------------------------------------------------------------------------------------------------------

_CODE = re.compile(r"`([^`]+)`")
_LINK = re.compile(r"\[([^\]]+)\]\(([^)\s]+)\)")
_AUTOLINK = re.compile(r"<(https?://[^>\s]+)>")
_BOLD = re.compile(r"\*\*(.+?)\*\*")
_ITALIC_STAR = re.compile(r"(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])")
_ITALIC_UNDER = re.compile(r"(?<![\w_])_(?!\s)(.+?)(?<!\s)_(?![\w_])")


def resolve_href(href: str) -> str:
    """Absolute and in-page links stay as they are; repo-relative ones point at the file on GitHub."""
    if re.match(r"^(https?:|mailto:|#)", href):
        return href
    return f"{REPO}/blob/main/{href.lstrip('./')}"


def inline(text: str) -> str:
    stash: list[str] = []

    def keep(markup: str) -> str:
        stash.append(markup)
        return f"\x00{len(stash) - 1}\x00"

    text = _CODE.sub(lambda m: keep(f"<code>{html.escape(m.group(1), quote=False)}</code>"), text)
    text = _AUTOLINK.sub(lambda m: keep(f'<a href="{html.escape(m.group(1))}">{html.escape(m.group(1), quote=False)}</a>'), text)
    # Keep the destination out of reach of the emphasis rules (file names contain underscores).
    text = _LINK.sub(lambda m: f'\x01{keep(html.escape(resolve_href(m.group(2))))}\x01{m.group(1)}\x02', text)
    text = html.escape(text, quote=False)
    text = _BOLD.sub(r"<strong>\1</strong>", text)
    text = _ITALIC_STAR.sub(r"<em>\1</em>", text)
    text = _ITALIC_UNDER.sub(r"<em>\1</em>", text)
    text = re.sub(r"\x01(\x00\d+\x00)\x01(.*?)\x02", lambda m: f'<a href="{m.group(1)}">{m.group(2)}</a>', text)
    while True:  # placeholders can nest (a code span inside a link label)
        replaced = re.sub(r"\x00(\d+)\x00", lambda m: stash[int(m.group(1))], text)
        if replaced == text:
            return replaced
        text = replaced


def plain(text: str) -> str:
    """Inline Markdown reduced to plain text, for the meta description."""
    text = _LINK.sub(r"\1", text)
    text = _CODE.sub(r"\1", text)
    text = _BOLD.sub(r"\1", text)
    text = _ITALIC_STAR.sub(r"\1", text)
    text = _ITALIC_UNDER.sub(r"\1", text)
    return re.sub(r"\s+", " ", text).strip()


# --------------------------------------------------------------------------------------------------------------------
# Blocks
# --------------------------------------------------------------------------------------------------------------------

_HEADING = re.compile(r"^(#{1,4})\s+(.*?)\s*#*\s*$")
_BULLET = re.compile(r"^(\s*)[-*+]\s+(.*)$")
_ORDERED = re.compile(r"^(\s*)\d+[.)]\s+(.*)$")
_TABLE_SEP = re.compile(r"^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$")
_HR = re.compile(r"^\s*([-*_])(\s*\1){2,}\s*$")


def slugify(text: str, used: set[str]) -> str:
    base = re.sub(r"[^a-z0-9]+", "-", plain(text).lower()).strip("-") or "section"
    slug, n = base, 2
    while slug in used:
        slug, n = f"{base}-{n}", n + 1
    used.add(slug)
    return slug


def split_row(line: str) -> list[str]:
    line = line.strip()
    if line.startswith("|"):
        line = line[1:]
    if line.endswith("|"):
        line = line[:-1]
    return [c.strip().replace("\\|", "|") for c in re.split(r"(?<!\\)\|", line)]


def convert(markdown: str) -> tuple[str, str, str]:
    """Return (title, first_paragraph_plain_text, body_html)."""
    lines = markdown.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    out: list[str] = []
    used_ids: set[str] = set()
    title = ""
    first_paragraph = ""
    i = 0

    def paragraph_text(start: int) -> tuple[str, int]:
        buf = []
        j = start
        while j < len(lines) and lines[j].strip() and not _starts_block(lines, j):
            buf.append(lines[j].strip())
            j += 1
        return " ".join(buf), j

    while i < len(lines):
        line = lines[i]
        if not line.strip():
            i += 1
            continue

        if line.lstrip().startswith("```"):
            fence = line.lstrip()[:3]
            i += 1
            code = []
            while i < len(lines) and not lines[i].lstrip().startswith(fence):
                code.append(lines[i])
                i += 1
            i += 1
            out.append(f"<pre><code>{html.escape(chr(10).join(code), quote=False)}</code></pre>")
            continue

        m = _HEADING.match(line)
        if m:
            level, text = len(m.group(1)), m.group(2)
            if level == 1 and not title:
                title = plain(text)
            ident = slugify(text, used_ids)
            out.append(f'<h{level} id="{ident}">{inline(text)}</h{level}>')
            i += 1
            continue

        if _HR.match(line):
            out.append("<hr>")
            i += 1
            continue

        if line.lstrip().startswith(">"):
            quote = []
            while i < len(lines) and lines[i].lstrip().startswith(">"):
                quote.append(re.sub(r"^\s*>\s?", "", lines[i]))
                i += 1
            out.append(f"<blockquote>\n{_body(quote)}\n</blockquote>")
            continue

        if "|" in line and i + 1 < len(lines) and _TABLE_SEP.match(lines[i + 1]):
            header = split_row(line)
            i += 2
            rows = []
            while i < len(lines) and lines[i].strip() and "|" in lines[i]:
                rows.append(split_row(lines[i]))
                i += 1
            width = len(header)
            head = "".join(f'<th scope="col">{inline(c)}</th>' for c in header)
            body = "\n".join(
                "<tr>" + "".join(f"<td>{inline(c)}</td>" for c in (r + [""] * width)[:width]) + "</tr>" for r in rows
            )
            out.append(
                f'<div class="table-scroll"><table>\n<thead><tr>{head}</tr></thead>\n<tbody>\n{body}\n</tbody>\n</table></div>'
            )
            continue

        if _BULLET.match(line) or _ORDERED.match(line):
            ordered = bool(_ORDERED.match(line)) and not _BULLET.match(line)
            tag = "ol" if ordered else "ul"
            pattern = _ORDERED if ordered else _BULLET
            items: list[list[str]] = []
            while i < len(lines):
                cur = lines[i]
                mm = pattern.match(cur)
                if mm:
                    items.append([mm.group(2).strip()])
                    i += 1
                elif cur.strip() and cur.startswith((" ", "\t")) and items:  # continuation of the previous item
                    items[-1].append(cur.strip())
                    i += 1
                else:
                    break
            lis = "\n".join(f"<li>{inline(' '.join(parts))}</li>" for parts in items)
            out.append(f"<{tag}>\n{lis}\n</{tag}>")
            continue

        text, i = paragraph_text(i)
        if text:
            if not first_paragraph and not re.fullmatch(r"[_*].*[_*]", text):
                first_paragraph = plain(text)
            out.append(f"<p>{inline(text)}</p>")
        else:  # a line the paragraph reader refused to take; never loop forever
            out.append(f"<p>{inline(line.strip())}</p>")
            i += 1

    return title, first_paragraph, "\n".join(out)


def _starts_block(lines: list[str], j: int) -> bool:
    line = lines[j]
    return bool(
        _HEADING.match(line)
        or _BULLET.match(line)
        or _ORDERED.match(line)
        or _HR.match(line)
        or line.lstrip().startswith(("```", ">"))
        or ("|" in line and j + 1 < len(lines) and _TABLE_SEP.match(lines[j + 1]))
    )


def _body(lines: list[str]) -> str:
    return convert("\n".join(lines))[2]


# --------------------------------------------------------------------------------------------------------------------
# Page
# --------------------------------------------------------------------------------------------------------------------

EXTRA_CSS = """
.prose { max-width: 760px; padding-top: 40px; padding-bottom: 8px; }
.prose h1 { font-size: clamp(1.8rem, 6vw, 2.6rem); line-height: 1.15; margin: 0 0 16px; letter-spacing: -.02em; }
.prose h2 { font-size: 1.45rem; line-height: 1.25; margin: 40px 0 12px; }
.prose h3 { font-size: 1.15rem; margin: 28px 0 8px; }
.prose p, .prose li { max-width: 68ch; }
.prose ul, .prose ol { padding-left: 1.3em; }
.prose li { margin: 6px 0; }
.prose hr { border: 0; border-top: 1px solid var(--border); margin: 32px 0; }
.prose blockquote { margin: 16px 0; padding: 4px 18px; border-left: 4px solid var(--peach); background: var(--surface-2); border-radius: 0 12px 12px 0; }
.prose .table-scroll { margin: 16px 0; }
.prose .table-scroll table { min-width: 0; }
.prose th, .prose td { overflow-wrap: anywhere; }
.page-top header .wrap { padding-bottom: 14px; }
"""


def shared_css() -> str:
    """The design tokens and components live in docs/index.html; reuse them so the two pages cannot drift apart."""
    page = INDEX.read_text(encoding="utf-8")
    m = re.search(r"<style>\n(.*?)</style>", page, re.S)
    if not m:
        raise SystemExit(f"Could not find the <style> block in {INDEX}")
    return m.group(1).rstrip("\n")


def description_from(first_paragraph: str) -> str:
    prefix = "Privacy policy for WakeNudge, the free open-source Android alarm clock. "
    text = prefix + first_paragraph
    if len(text) <= 155:
        return text
    cut = text[:152].rsplit(" ", 1)[0].rstrip(" ,;:.-")
    return cut + "..."


def render(markdown: str) -> str:
    title, first_paragraph, body = convert(markdown)
    page_title = f"{title or 'Privacy Policy'} - WakeNudge"
    if len(page_title) > 60:
        page_title = "Privacy Policy - WakeNudge"
    description = html.escape(description_from(first_paragraph))
    css = shared_css() + "\n" + EXTRA_CSS.strip("\n")
    page_title_html = html.escape(page_title)
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{page_title_html}</title>
<meta name="description" content="{description}">
<link rel="canonical" href="{CANONICAL}">
<meta name="color-scheme" content="light dark">
<meta name="theme-color" content="#3d2f9e" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#14112e" media="(prefers-color-scheme: dark)">
<meta name="robots" content="index, follow">
<link rel="icon" href="assets/favicon.ico" sizes="48x48">
<link rel="icon" type="image/png" sizes="32x32" href="assets/favicon-32.png">
<link rel="apple-touch-icon" href="assets/apple-touch-icon.png">
<meta property="og:type" content="website">
<meta property="og:site_name" content="WakeNudge">
<meta property="og:title" content="{page_title_html}">
<meta property="og:description" content="{description}">
<meta property="og:url" content="{CANONICAL}">
<meta property="og:image" content="{OG_IMAGE}">
<meta name="twitter:card" content="summary">
<meta name="twitter:title" content="{page_title_html}">
<meta name="twitter:description" content="{description}">
<style>
{css}
</style>
</head>
<body>
<a class="skip" href="#main">Skip to content</a>
<div class="top page-top">
<header>
  <div class="wrap">
    <a class="brand" href="./"><img src="assets/icon-192.png" width="36" height="36" alt="">WakeNudge</a>
    <nav aria-label="Main">
      <ul>
        <li><a href="./">Home</a></li>
        <li><a href="./#download">Download</a></li>
        <li><a href="{REPO}">GitHub</a></li>
      </ul>
    </nav>
  </div>
</header>
</div>
<main id="main" class="wrap prose">
{body}
</main>
<footer>
  <div class="wrap">
    <ul>
      <li><a href="./">WakeNudge home</a></li>
      <li><a href="{REPO}">Source on GitHub</a></li>
      <li><a href="{REPO}/blob/main/PRIVACY.md">This policy on GitHub</a></li>
      <li><a href="{REPO}/security/policy">Security policy</a></li>
    </ul>
    <p>WakeNudge is free software under the <a href="{REPO}/blob/main/LICENSE">Apache License 2.0</a>. Copyright 2026 riyadm.</p>
  </div>
</footer>
</body>
</html>
"""


def main() -> int:
    page = render(SOURCE.read_text(encoding="utf-8"))
    if "--check" in sys.argv[1:]:
        current = TARGET.read_text(encoding="utf-8") if TARGET.exists() else ""
        if current != page:
            print(f"{TARGET.relative_to(ROOT)} is out of date; run: python3 -I scripts/build-privacy-page.py", file=sys.stderr)
            return 1
        print(f"{TARGET.relative_to(ROOT)} is up to date")
        return 0
    TARGET.write_text(page, encoding="utf-8", newline="\n")
    print(f"wrote {TARGET.relative_to(ROOT)} ({len(page.encode('utf-8'))} bytes) from {SOURCE.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
