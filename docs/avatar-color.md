# Avatar color

Normative. Written to be reimplemented in other Dumble codebases so every client shows the same
person the same color. A hash plus a literal 16-entry table: no color-space math at the call site,
so porting is copying an array.

This file is the source of truth. `Fnv1a.kt` and `AvatarPalette.kt` implement it; `Fnv1aTest.kt` and
`AvatarPaletteTest.kt` pin them to the vectors below. Change nothing in those files without changing
this one.

## Algorithm

```
colorForDisplayName(name):
  1. bytes = UTF-8 encoding of name       # not UTF-16 code units — that is where platforms differ
  2. h = 0x811C9DC5                       # FNV-1a, 32-bit
     for each byte b in bytes:
         h = ((h XOR b) * 0x01000193) mod 2^32
  3. background = PALETTE[h mod 16]
  4. foreground = #FFFFFF                 # every palette entry clears 4.5:1 against white
```

```
PALETTE = [ #9C5A6F, #A05B59, #9D6044, #946733,
            #866F2C, #727734, #5A7D46, #3F815D,
            #218373, #128188, #277C99, #4376A3,
            #5D6FA6, #7367A2, #856196, #935C84 ]
```

The name is **not** lower-cased and **not** trimmed: Mumble names are case-sensitive, and two users
differing only in case are different people who should look different. Keyed on the name rather than
the session id, so a color survives a reconnect — session ids are per-connection and would reshuffle
every time.

## Test vectors

For verifying another implementation.

| name | FNV-1a 32 | index | color |
|---|---|---|---|
| `` (empty) | 2166136261 | 5 | `#727734` |
| `a` | 3826002220 | 12 | `#5D6FA6` |
| `alice` | 2267157479 | 7 | `#3F815D` |
| `bob` | 2261164244 | 4 | `#866F2C` |
| `DanDesktop` | 3818523584 | 0 | `#9C5A6F` |
| `DanRelease` | 2530977605 | 5 | `#727734` |
| `Zoë` | 3265445340 | 12 | `#5D6FA6` |
| `日本語` | 2153733351 | 7 | `#3F815D` |

The non-ASCII vectors catch an implementation that hashed UTF-16 code units; the empty-string vector
pins the FNV offset basis. `DanDesktop` and `DanRelease` landing on different entries, and
`DanRelease` colliding with the empty string, are real outputs rather than tuned examples.

Two rows are not specific to this project at all: the empty string must return the offset basis by
definition, and `a` → `0xe40c292c` is FNV-1a's own published vector. A failure on either means a
broken hash rather than a changed table.

## Why FNV-1a and not the platform hash

The original Objective-C used `[NSString hash]`, which Cocoa explicitly leaves unspecified and which
may change between OS releases; Java's `String.hashCode()` *is* specified but is a different
function, so the two platforms could never agree. FNV-1a is five lines, dependency-free, and
identical in every language. Hashing UTF-8 bytes rather than the platform's string units is the other
half of that agreement.

## How the table was generated

Sixteen hues spaced evenly in **OKLCh at L=0.55, C=0.090**, converted to sRGB. Even spacing in a
perceptually uniform space rather than HSV, whose hue degrees are badly non-uniform — evenly spaced
HSV hues measured a **4–5×** spread between the closest and furthest adjacent pairs (greens sprawl,
oranges change fast). C=0.090 is the largest chroma that stays inside sRGB at that lightness.

**Why 16 and not 360.** A continuous hue rotation puts neighbours at ΔE 0.0015, far below a
just-noticeable difference. Sixteen gives adjacent ΔE 0.0351, comparable to the hand-curated
separation in AOSP's `letter_tile_colors` (14 colors, closest pair 0.0477). Collisions are frequent
at 16 — roughly a **66% chance two of six users share a color** — and that is accepted: the name and
initial are the identifier, color is a recognition aid. Confusable colors are the worse failure,
because the user cannot tell it is happening. AOSP ships 14 for the same reason.

If more colors are ever wanted, add a second lightness band rather than slicing the hue wheel finer:
16 hues × 2 lightnesses measured ΔE 0.0410, better separated than 24 pure hues (0.0326) and far
better than 32 (0.0245).

**Why one palette rather than a light/dark pair.** Choosing white initials forces a mid-dark palette,
which sits acceptably on both surfaces — 4.51–4.96:1 on the light surface, 3.67–4.04:1 on the dark
one. A lighter pastel set (L=0.74) would need a per-theme pair: it measured a calm 2.12–2.38:1 on
light but a glaring 7.65–8.58:1 on dark. White initials against the shipped table measure
**4.607–5.116:1**, clearing WCAG AA everywhere with no per-color branch to implement.

Note the app's theme follows Material You dynamic color on API 31+, so the *theme* comes from the
wallpaper while the avatar palette stays fixed. That is deliberate: these are identity colors, not
theme colors — a wallpaper change must not repaint who everyone is. Google Contacts does the same.

## Open: the table is not AOSP-like

Recorded because it is the one part still under review, not a defect. The shipped table measures:

| | chroma mean | lightness spread | min pairwise ΔE |
|---|---|---|---|
| AOSP `letter_tile_colors` (14) | 0.159 | 0.21 | 0.0477 |
| Shipped table (constant L and C) | 0.090 | 0.00 | 0.0335 |
| Per-hue maximum chroma | 0.200 | 0.21 | 0.0456 |

Holding lightness *and* chroma constant gives every hue the worst hue's gamut ceiling, which is why
the table reads muted next to AOSP's Material 500s — sRGB reaches far higher chroma in blues than in
yellows, so a designer varies lightness per hue to chase it. Taking the per-hue maximum overshoots
into neon. Regenerating AOSP-style is a drop-in replacement — same algorithm, same vectors for the
hash column, new literals — gated by `AvatarPaletteTest`'s contrast assertion.
