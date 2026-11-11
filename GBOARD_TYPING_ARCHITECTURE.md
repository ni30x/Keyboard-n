# The Architecture of Modern High-Speed Virtual Keyboards: How Gboard Works

This document provides a comprehensive, production-grade technical analysis of the mathematics, algorithms, and micro-architectural systems that power modern high-speed mobile keyboards like Google Gboard. It explains why traditional on-screen keyboards feel "block-like" and stiff with dead zones, and how Gboard achieves near-zero letter mismatch, smooth gap-tapping, fast swipe/drawing input, and sub-16ms latency.

---

## Table of Contents
1. [The "Block-Like" Problem: Why Naive Keyboards Fail](#1-the-block-like-problem-why-naive-keyboards-fail)
2. [Continuous Spatial Touch Modeling & Voronoi Tessellation](#2-continuous-spatial-touch-modeling--voronoi-tessellation)
3. [The Bivariate Gaussian Hit-Testing Model](#3-the-bivariate-gaussian-hit-testing-model)
4. [Joint Probabilistic Decoding: Combining Touch and Language](#4-joint-probabilistic-decoding-combining-touch-and-language)
5. [The Beam Search Decoder & Token Passing (WFST)](#5-the-beam-search-decoder--token-passing-wfst)
6. [How Continuous One-Hand Drawing Works (Gesture & Handwriting)](#6-how-continuous-one-hand-drawing-works-gesture--handwriting)
7. [Sub-16ms Engineering: The Low-Latency Touch-to-Pixel Pipeline](#7-sub-16ms-engineering-the-low-latency-touch-to-pixel-pipeline)
8. [Ergonomics: Key Popups, Visual Occlusion, and Muscle Memory](#8-ergonomics-key-popups-visual-occlusion-and-muscle-memory)

---

## 1. The "Block-Like" Problem: Why Naive Keyboards Fail

Traditional Android keyboards (like the legacy Android AOSP `KeyboardView`) treat keys as **rigid, disconnected rectangular bounding boxes**:

```
Traditional Bounding Box Keyboard:
┌──────┐  gap  ┌──────┐  gap  ┌──────┐
│  D   │ <---> │  F   │ <---> │  G   │
└──────┘       └──────┘       └──────┘
    ▲              ▲
    │              └── DEAD ZONE: Touch here is dropped or missed!
    └───────────────── STRICT BOUNDARY: Must land strictly inside rect
```

### The Three Fundamental Flaws of Naive Keyboards:
1. **The Inter-Key Dead Zone (Gaps)**:
   Between each key rectangle lies a 2dp–6dp gutter, plus keyboard margins at the edges. In naive implementations, `key.isInside(x, y)` tests `x >= key.x && x < key.x + key.width`. If the user's thumb lands in the gutter, **no key triggers**, or the touch is arbitrarily swallowed. The user experiences this as "dropped letters" or "unresponsive typing".
2. **Binary Quantization**:
   A tap at $(x, y)$ that lands 1 pixel inside key 'D' is registered with 100% confidence as 'D', while a tap 1 pixel to the right (inside the gap or border of 'F') is either dropped or registered as 'F'. In reality, human finger contacts are soft, fleshy ellipses of 8mm–12mm diameter. The actual point of intended contact is a continuous probability distribution, not a binary integer.
3. **Absence of Linguistic Context During Touch Dispatch**:
   If the user has already typed `"hel"`, a tap equidistant between `'l'` and `'k'` is almost certainly `'l'` (`"hell"` / `"hello"`), never `'k'` (`"helk"`). Rigid keyboards lack any real-time coupling between the touch sensor coordinates and the predictive language model.

---

## 2. Continuous Spatial Touch Modeling & Voronoi Tessellation

To eliminate dead zones, modern keyboards model the entire keyboard surface as a **continuous, space-filling tessellation**. Every single pixel on the screen belongs to a key's basin of attraction.

```
Voronoi Partition of Keyboard Space:
┌─────────────────┬──────────────────┬─────────────────┐
│                 │                  │                 │
│        D        │        F         │        G        │
│        •        │        •         │        •        │
│   (Centroid)    │   (Centroid)     │   (Centroid)    │
│                 │                  │                 │
└─────────────────┴──────────────────┴─────────────────┘
                  ▲
                  └── ZERO DEAD SPACE: The boundary is an infinitesimal line.
                      Every point in the gap belongs to the nearest centroid.
```

### Mathematical Formulation
Given a set of key centroids $C = \{c_1, c_2, \dots, c_n\}$ where $c_i = (x_i, y_i) \in \mathbb{R}^2$, the Voronoi cell $V(c_i)$ is defined as:

$$V(c_i) = \{ p \in \mathbb{R}^2 \mid \|p - c_i\| \le \|p - c_j\|, \forall j \ne i \}$$

Where:
- $p = (x, y)$ is the raw touch coordinate from the digitizer.
- $\|p - c_i\|$ is the Euclidean distance $\sqrt{(x - x_i)^2 + (y - y_i)^2}$.

By replacing discrete bounding-box checks with Voronoi distance partitioning, **there are zero dead zones**. Tapping in a gap between `'D'` and `'F'` immediately maps to the geometrically closest key centroid.

---

## 3. The Bivariate Gaussian Hit-Testing Model

Human finger contacts are not isotropic points; they are oriented ellipses. Furthermore, typing with thumbs exhibits systematic spatial bias:
- Right thumbs drift toward the bottom-left of intended keys.
- Left thumbs drift toward the bottom-right.
- Tap dispersion is wider horizontally than vertically.

```
Touch Point Distribution on a Key:
┌─────────────────────────────────┐
│ Key Boundary                    │
│             ╭───────╮           │
│           ╭─╯       ╰─╮         │
│          │   Centroid  │        │
│          │      •      │        │
│          │  (Peak Prob)│        │
│           ╰─╮       ╭─╯         │
│             ╰───────╯           │
│       Gaussian Falloff          │
└─────────────────────────────────┘
```

Instead of simple Euclidean distance, Gboard uses a **Bivariate Gaussian Probability Density Function** for each key $k$:

$$P(S = (x, y) \mid K = k) = \frac{1}{2\pi \sigma_{x,k} \sigma_{y,k} \sqrt{1 - \rho_k^2}} \exp\left( -\frac{z}{2(1 - \rho_k^2)} \right)$$

Where $z$ is the normalized elliptical distance:

$$z = \frac{(x - \mu_{x,k})^2}{\sigma_{x,k}^2} - \frac{2\rho_k(x - \mu_{x,k})(y - \mu_{y,k})}{\sigma_{x,k}\sigma_{y,k}} + \frac{(y - \mu_{y,k})^2}{\sigma_{y,k}^2}$$

### Dynamic Parameter Adaptation:
- $\mu_{x,k}, \mu_{y,k}$: The effective centroid of key $k$, adjusted for user-specific finger offset (measured during typing).
- $\sigma_{x,k}$: Horizontal touch variance ($\approx 0.50 \times \text{keyWidth}$).
- $\sigma_{y,k}$: Vertical touch variance ($\approx 0.52 \times \text{keyHeight}$).
- $\rho_k$: Correlation coefficient representing the angle of the thumb approach.

When a touch lands anywhere in the keyboard area, every nearby candidate key receives a spatial score $P(S \mid K) \in (0, 1]$.

---

## 4. Joint Probabilistic Decoding: Combining Touch and Language

The secret behind Gboard's zero letter mismatch is **Bayesian Joint Probabilistic Decoding**. The keyboard does not ask: *"Which key did the finger touch?"*
It asks: *"Given the touch stream $S_{1:t}$ and the language context $C$, what is the most probable intended word $W$?"*

By Bayes' Rule:

$$P(W \mid S) = \frac{P(S \mid W) \cdot P(W)}{P(S)} \propto P(S \mid W) \cdot P(W)$$

Where:
1. **$P(S \mid W)$ is the Spatial Model**:
   The likelihood that the sequence of physical touch points $S = (s_1, s_2, \dots, s_m)$ was generated by attempting to type word $W = (c_1, c_2, \dots, c_m)$:
   $$P(S \mid W) = \prod_{i=1}^{m} P(s_i \mid c_i)$$
2. **$P(W)$ is the Language Model**:
   The prior probability of the word sequence according to an on-device n-gram or recurrent/Transformer neural language model:
   $$P(W) = P(w_t \mid w_{t-1}, w_{t-2})$$

### Concrete Example:
Suppose the user has typed `"th"` and aims for `'e'`. Their thumb lands in the gap between `'e'` and `'w'`, physically slightly closer to `'w'`:
- Spatial Likelihood: $P(s \mid 'w') = 0.55$, while $P(s \mid 'e') = 0.45$.
- Language Prior: In English, after `"th"`, $P("the") = 0.68$, while $P("thw") = 0.0001$.
- Combined Score:
  $$\text{Score}('e') = 0.45 \times 0.68 = 0.306$$
  $$\text{Score}('w') = 0.55 \times 0.0001 = 0.000055$$

The engine outputs `'e'` with overwhelming certainty! To the user, it feels as if the keyboard read their mind.

---

## 5. The Beam Search Decoder & Token Passing (WFST)

To evaluate thousands of candidate words across multiple keystrokes in real time, Gboard compiles its dictionary and grammar into a **Weighted Finite-State Transducer (WFST)** or prefix trie, traversed using **Beam Search Token Passing**.

```
Beam Search Lattice:
  Touch 1 (near T)       Touch 2 (near H)       Touch 3 (between E and W)
  
  ┌───────────┐          ┌───────────┐          ┌───────────┐
  │ Token 't' │ ───────> │ Token 'th'│ ───────> │ Token 'the'│ (Kept in beam)
  │ (score 0.9)│         │(score 0.82)│        │(score 0.78)│
  └───────────┘          └───────────┘          └───────────┘
        │                      │
        ▼                      ▼                ┌───────────┐
  ┌───────────┐          ┌───────────┐          │ Token 'thw'│ (Pruned out!)
  │ Token 'y' │          │ Token 'ty'│ ───────> │(score 0.001)
  │ (score 0.1)│         │(score 0.04)│        └───────────┘
  └───────────┘          └───────────┘
```

### Token Passing Rules:
1. At each touch event $t$, existing tokens in active states propagate forward to neighbor states along valid lexicon transitions.
2. The token's path score is updated:
   $$\text{Score}_{\text{new}} = \text{Score}_{\text{old}} + \ln P(s_t \mid c_{\text{target}}) + \alpha \ln P(c_{\text{target}} \mid \text{history})$$
3. **Beam Pruning**: Only the top $K$ tokens (typically $K = 16$ to $64$) with the highest cumulative log-scores are retained. Low-probability paths are pruned immediately, keeping memory usage constant and computation bounded under $2\text{ms}$ per tap.

---

## 6. How Continuous One-Hand Drawing Works (Gesture & Handwriting)

Gboard allows "drawing" words via glide typing (swiping across letters) or direct on-screen cursive handwriting. Both utilize continuous spatio-temporal stroke decoders.

```
Continuous Gesture Trajectory:
   (Q)      (W)      (E)      (R)      (T)
                     ●───╮
                         │
   (A)      (S)      (D) │    (F)      (G)
                         ╰─────────────●
                                      (H)
Trajectory Curve: [s(t₀), s(t₁), ..., s(tₖ)]
Resampled into equidistant spatial arc-length points: d(pᵢ, pᵢ₊₁) = Δs
```

### The 4-Stage Stroke Recognition Pipeline:
1. **Temporal-to-Spatial Resampling**:
   Touch digitizers deliver samples at uneven time intervals (e.g., $120\text{Hz}$ or $240\text{Hz}$). The raw stroke points $(x_i, y_i, t_i)$ are resampled along the cumulative arc length so that sample points are spaced evenly in distance (e.g., every $5\text{dp}$). This eliminates finger speed artifacts.
2. **Feature Extraction (Inflection & Curvature Points)**:
   The algorithm detects sharp corners and deceleration points:
   $$\kappa(t) = \frac{|\dot{x}\ddot{y} - \dot{y}\ddot{x}|}{(\dot{x}^2 + \dot{y}^2)^{3/2}}$$
   Points of maximum curvature $\kappa$ and near-zero velocity correspond to intentional key targets (corners where the user turned toward a letter).
3. **Dynamic Time Warping (DTW) / CTC Neural Sequence Alignment**:
   The resampled gesture curve is aligned against idealized paths representing dictionary words using continuous Dynamic Time Warping:
   $$D(i, j) = \text{cost}(p_i, k_j) + \min(D(i-1, j), D(i, j-1), D(i-1, j-1))$$
4. **Pruning & Word Scoring**:
   The shape distance score is combined with the language model prior, producing the top candidates in the suggestion strip in under $10\text{ms}$ upon finger lift.

---

## 7. Sub-16ms Engineering: The Low-Latency Touch-to-Pixel Pipeline

A keyboard must display the pressed key state on the very next refresh frame ($16.6\text{ms}$ at $60\text{Hz}$, $8.3\text{ms}$ at $120\text{Hz}$). If the keyboard lags even 2 frames, the user perceives typing as "sluggish" or "sticky".

```
Low-Latency Multi-Thread Architecture:
 Touch Screen
      │ MotionEvent (DOWN)
      ▼
┌────────────────────────────────────────────────────────┐
│ UI Thread (< 4ms budget)                               │
│  1. Unbuffered batch reading (event.getHistoricalX)    │
│  2. Direct Voronoi / spatial hit resolution            │
│  3. Invalidate ONLY key dirty rect (invalidate(Rect))  │
│  4. Dispatch haptic click (Vibrator.vibrate)           │
└──────────────────────────┬─────────────────────────────┘
                           │ Dispatches touch coordinate
                           ▼
┌────────────────────────────────────────────────────────┐
│ Background Worker Thread (Decoder)                     │
│  1. Beam search expansion on Trie / WFST               │
│  2. Suggestion ranking & auto-correction               │
│  3. Posts top 3 suggestions back to UI thread          │
└────────────────────────────────────────────────────────┘
```

### Critical Optimization Principles:
- **Zero Allocations in Hot Paths**:
  `onTouchEvent` and `onDraw` must allocate zero objects. No `new Point()`, no `RectF()`, no lambda closures. All matrices, coordinates, and paint styles are pre-allocated during layout initialization.
- **Partial Invalidation**:
  Never call `invalidate()` on the entire keyboard view on a key tap. Only invalidate the dirty bounding box of the touched key:
  `keyboardView.invalidate(key.x, key.y, key.x + key.width, key.y + key.height)`
- **Immediate Sensory Feedback on DOWN**:
  Visual press state and haptic pulse execute instantaneously on `ACTION_DOWN`. Complex word prediction and suggestion ranking happen asynchronously and never block the main UI thread.

---

## 8. Ergonomics: Key Popups, Visual Occlusion, and Muscle Memory

### Why Big Popups Feel Bad:
Older keyboards spawned a massive `PopupWindow` balloon floating $40\text{dp}$ above the pressed key. This causes:
1. **Visual Occlusion**: The popup covers the word suggestion bar or the text field above the keyboard.
2. **Context Distraction**: A large bright box flashing for every single character draws the user's eye away from the text they are composing.
3. **Window Manager Overhead**: Allocating and drawing a secondary Android `Window` involves inter-process communication with `SurfaceFlinger`, creating micro-stutters.

### The Gboard Approach (Refined In-View Key Preview):
1. **Drawn In-View**: Key previews are rendered directly on the keyboard's own Canvas surface (zero Window Manager overhead).
2. **Proportional Slimming**: The popup bubble is barely larger than the key itself (width $\approx 1.08 \times$, height $\approx 1.12 \times$).
3. **Instant Response & Rapid Decay**: The preview appears on `ACTION_DOWN` and immediately begins a non-blocking fade-out upon `ACTION_UP` ($80\text{ms}$ decay), providing crystal-clear visual confirmation without visual clutter.

---

## Summary
Gboard feels fast, forgiving, and smooth not because of magic, but because of:
1. **Continuous Voronoi & Gaussian hit-testing** that erases all inter-key dead zones.
2. **Joint spatial-linguistic decoding** that corrects finger inaccuracies using grammatical probability.
3. **WFST beam search** that evaluates thousands of paths within $2\text{ms}$.
4. **Zero-allocation 120 FPS graphics rendering** with slim, in-view feedback badges.
