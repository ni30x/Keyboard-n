# Gboard Layout Geometry Calibration

Calibrate the keyboard's vertical key height, row pitch, vertical gaps, toolbar alignment, and total container height so that key sizing and row spacing match Gboard (portrait, default height) with pixel-perfect precision based on device screenshot measurements.

## Measured Gboard Geometry (1080x2400 Display, Scale = 2.625)

| Metric | Measured Gboard (px) | Equivalent (dp) | Implementation in Code |
| :--- | :--- | :--- | :--- |
| **Top of Toolbar (Y-coordinate)** | `Y = 1512` | — | Top border of keyboard surface |
| **Toolbar Height (`toolbar_height`)** | `116px` | `44dp` | `@dimen/toolbar_height` = `44dp` |
| **Top Padding Above Row 1** | `26px` | `10dp` | `@dimen/keyboard_top_padding` = `10dp` |
| **Row 1 Top (Y-coordinate)** | `Y = 1654` | — | `1512 + 116 + 26 = 1654px` (exact) |
| **Key Height (`key_height`)** | `123px` | `47dp` | `@dimen/key_height` = `47dp` |
| **Vertical Row Gap (`row_gap`)** | `32px` | `12dp` | `@dimen/row_gap` = `12dp` |
| **Row Pitch (`row_pitch`)** | `155px` | `59dp` | `47dp + 12dp = 59dp` ($155 / 2.625 = 59.04\text{dp}$) |
| **Keys Area Height (Row 1 to Row 4)** | `588px` | `224dp` | $4 \times 47\text{dp} + 3 \times 12\text{dp} = 224\text{dp}$ |
| **Gap Below Row 4** | `32px` | `12dp` | `@dimen/gap_below_row4` = `12dp` |
| **Keyboard View Height** | `646px` | `246dp` | `10dp + 224dp + 12dp = 246dp` |
| **System Navigation Bar / Insets** | `126px` | `48dp` | `WindowInsets.Type.systemBars().bottom` |
| **Total Keyboard + Inset Height** | `888px` | `338dp` | `44dp + 246dp + 48dp = 338dp` ($2400 - 888 = 1512$) |
| **Corner Radius (Letter)** | `21px` | `8dp` | `@dimen/key_corner_radius` = `8dp` |
| **Corner Radius (Pill: ?123, Enter)** | `62px` | `23.5dp` | `@dimen/key_pill_corner_radius` = `23.5dp` (`key_height / 2`) |
