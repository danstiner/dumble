# Avatar color

**Normative.** This page is the specification, written so that other ports of this app can show the
same person in the same color. `Fnv1a.kt` and `AvatarPalette.kt` implement it, and `Fnv1aTest.kt`
and `AvatarPaletteTest.kt` pin them to the vectors below. Change this page and those files together.

## Goals

- **The same name gets the same color on every platform,** with nothing to compute but a hash.
  Porting is copying an array.
- **Neighbouring colors are distinguishable.** Two people who look almost the same are worse than
  two who look identical, because the user cannot tell it is happening.
- **White initials are readable on every color,** with no per-color branch.
- **A color survives a reconnect and a wallpaper change.**

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

The name is **not** lower-cased and **not** trimmed. Mumble names are case-sensitive, and two users
who differ only in case are different people.

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

- The non-ASCII rows catch an implementation that hashed UTF-16 code units.
- Two rows are properties of FNV-1a itself: the empty string must return the offset basis, and
  `a` → `0xe40c292c` is the hash's own published vector. A failure on either means a broken hash,
  not a changed table.
- `DanRelease` colliding with the empty string is a real output, not a tuned example.

## Decisions

| Decision | Why | Cost |
|---|---|---|
| Keyed on the display name, not the session id | Session ids change on every connection, so colors would reshuffle on each reconnect. | Renaming changes the color. |
| FNV-1a over UTF-8 bytes | It is five lines, has no dependencies and is identical in every language. Platform string hashes cannot agree: Cocoa's `[NSString hash]` is unspecified and may change between OS releases, and Java's `String.hashCode()` is a different function. | None. |
| A literal 16-entry table | No color-space math at the call site. | Regenerating the table means editing literals on every platform. |
| 16 colors, not a continuous hue | A continuous rotation puts neighbours at ΔE 0.0015, far below a noticeable difference. | With six users there is about a 66% chance two share a color. |
| One palette for light and dark themes | A mid-dark palette is acceptable on both surfaces, and white initials clear contrast on all of it. | The palette is mid-dark on both themes; a lighter set would need one palette per theme. |
| Fixed, not derived from the wallpaper theme | These are identity colors. A wallpaper change must not repaint who everyone is. | The avatars do not follow Material You. |

## How the table was generated

Sixteen hues evenly spaced in **OKLCh at L=0.55, C=0.090**, converted to sRGB.

- **OKLCh, not HSV.** HSV's hue degrees are far from perceptually uniform: evenly spaced HSV hues
  measured a 4–5× spread between the closest and furthest adjacent pairs.
- **C=0.090** is the largest chroma that stays inside sRGB at that lightness for every hue.
- **16** gives adjacent ΔE 0.0351, comparable to the hand-picked separation in AOSP's
  `letter_tile_colors` (14 colors, closest pair 0.0477). Collisions are accepted: the name and
  initial identify a person, and color only helps recognition.
- **Contrast.** White initials measure 4.607–5.116:1 against the table, which clears WCAG AA. The
  table itself measures 4.51–4.96:1 on the light surface and 3.67–4.04:1 on the dark one. A
  lighter pastel set (L=0.74) would need a palette per theme: it measured 2.12–2.38:1 on light
  and a glaring 7.65–8.58:1 on dark.

If more colors are ever wanted, add a second lightness band instead of slicing the hues finer.
16 hues × 2 lightnesses measured ΔE 0.0410, better than 24 pure hues (0.0326) and far better than
32 (0.0245).

## Open question: the table is more muted than AOSP's

Under review, not a defect.

| | chroma mean | lightness spread | min pairwise ΔE |
|---|---|---|---|
| AOSP `letter_tile_colors` (14) | 0.159 | 0.21 | 0.0477 |
| Shipped table (constant L and C) | 0.090 | 0.00 | 0.0335 |
| Per-hue maximum chroma | 0.200 | 0.21 | 0.0456 |

Holding lightness and chroma constant limits every hue to what the weakest hue can reach. sRGB
reaches much higher chroma in blues than in yellows, so a designer varies lightness per hue to use
it. Taking the per-hue maximum overshoots into neon. Regenerating in AOSP's style is a drop-in
replacement: same algorithm, same hash column in the vectors, new literals, gated by
`AvatarPaletteTest`'s contrast assertion.
