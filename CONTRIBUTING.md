# Contributing to PlayDepth

Thank you for your interest in **PlayDepth**.

## Project Policy

PlayDepth is a **personal project** published primarily for transparency and independent verification.

- Pull Requests for new features, UI redesigns, or architectural changes are generally not accepted.
- Please **open an Issue to discuss** before submitting a Pull Request; unsolicited PRs may be closed without merging.
- Small, focused bug-fix PRs may be considered on a case-by-case basis once discussed in an Issue.

---

## Reporting Bugs & DAC Compatibility

Bug reports and USB DAC feedback are always welcome. When opening an Issue, please include:

1. **App version** (found in **Settings → About**)
2. **Device model & Android version** (e.g., Pixel 8, Android 15)
3. **USB DAC model** (if applicable)
4. **Steps to reproduce** the issue
5. **Debug Log** exported from the app's **Settings** screen (if relevant)

---

## Submitting an Approved Fix

If a fix has been agreed upon in an Issue:

1. Follow the structure and style rules in [CONVENTIONS.md](CONVENTIONS.md).
2. Keep changes minimal and scoped strictly to the bug.
3. Verify the project builds and passes unit tests before opening the PR:

```bash
./gradlew assembleDebug testDebugUnitTest
```
