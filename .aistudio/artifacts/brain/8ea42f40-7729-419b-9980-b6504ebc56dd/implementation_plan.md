# Gboard-Matched Keyboard Sizing, Geometry & Styling Implementation Plan

## User Review & Clarified Preferences

> [!IMPORTANT]
> Based on your measurements and answers:
> - **Key Corner Radius**: Matching Gboard's softer rounded corners (~20–24px / 8–9dp) on letter keys, and **full stadium/pill shapes** on the `?123` mode change key and `Enter`/action key.
> - **Key Height & Row Spacing**: Exact Gboard proportions—reducing key height by ~7.5% (from 60dp to 55dp, giving ~132px top row and ~126px spacebar/enter) and reducing vertical row gap to 10px (~5dp total gap between rows).
> - **Bottom Row Geometry**: Replacing the standalone comma key left of the spacebar with `@` (with comma available in popup/long-press), and displaying the tab/next (`->|`) icon on the enter key when next action is active.

---

## Proposed Changes

### 1. Key Dimensions & Row Spacing (`dimens.xml` & Key Drawables)
- Update `R.dimen.key_height` in `app/src/main/res/values/dimens.xml`:
  - Adjust base height from `60dp` to `55dp` (~132px on 1080p scale), matching Gboard's ~7.5% more compact key profile.
- Introduce dedicated keyboard dimension tokens:
  - `key_corner_radius`: `8.5dp` (~22px, matching Gboard's 20–24px soft rounded corners).
  - `key_pill_corner_radius`: `24dp` (or `999dp` full stadium pill shape for `?123` and `Enter`).
  - `key_vertical_margin`: `2.5dp` (top `2.5dp`, bottom `2.5dp` for a total 5dp / ~10px inter-row vertical spacing, down from 14px).
  - `key_horizontal_margin`: `2.5dp` (for balanced horizontal key separation).

### 2. Pill/Stadium Shape for `?123` and Enter Keys (`drawables` & `MyKeyboardView.kt`)
- Create `keyboard_pill_key_background.xml` and `keyboard_pill_key_background_outlined.xml` with full stadium/pill shape (`corners radius="24dp"`).
- Update `keyboard_enter_background.xml` and `keyboard_enter_background_outlined.xml` to use the full stadium pill geometry.
- In `MyKeyboardView.kt`:
  - Enhance `setupKeyBackground(key, keyCode, canvas)`:
    - Route `KEYCODE_MODE_CHANGE` (`?123`) to use the pill background drawable with function key tinting.
    - Route `KEYCODE_ENTER` to use the enter pill background drawable with primary accent tinting.
  - Update `mKeyBackground` and pressed backgrounds to use `key_corner_radius` (8.5dp) for all letter and standard keys.

### 3. Bottom Row `@` Key & Tab/Next Action Icon
- In `app/src/main/res/xml/keys_letters_english_qwerty.xml`:
  - Update the key immediately to the left of the spacebar:
    - Change primary label from `,` to `@` (with `,` and tools available on popup/long press), matching Gboard's bottom row layout.
- Create vector drawable `ic_tab_next_vector.xml`:
  - High-precision Gboard-style Tab / Next arrow pointing to a vertical bar (`->|`).
- In `app/src/main/kotlin/org/fossify/keyboard/helpers/MyKeyboard.kt`:
  - When `key.code == KEYCODE_ENTER` and `mEnterKeyType` is `EditorInfo.IME_ACTION_NEXT`, assign `ic_tab_next_vector`.

---

## Verification Plan

### Automated Build & Unit Tests
- Execute `compile_applet` to ensure zero compilation or resource errors across layouts and drawables.
- Run unit tests via `gradle :app:testDebugUnitTest`.

### Visual & Geometry Verification
- Verify that standard keys display soft 8.5dp rounded corners.
- Verify that both `?123` and `Enter` render as smooth full pills/stadiums.
- Verify that the vertical gap between key rows measures 10px (~5dp).
- Verify that the `@` key renders to the left of the spacebar.
- Verify that `IME_ACTION_NEXT` correctly renders the `->|` tab icon on the Enter key.
