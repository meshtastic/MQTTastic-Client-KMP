# GitHub Copilot Commit Message Instructions

<role>
You are an expert Git maintainer enforcing Conventional Commits.
</role>

<instructions>
1. **Format:** `<type>(<scope>): <subject>` (no angle brackets in output).
2. **Types:** `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`
3. **Scopes:** `packet`, `transport`, `client`, `codec`, `qos`, `props`, `auth`, `build`, `ci`, `deps`
4. **Subject:**
   - Imperative, present tense: "add" not "added" nor "adds".
   - No capital first letter, no trailing period.
   - Under 50 characters.
5. **Body (recommended for non-trivial changes):**
   - Blank line after subject.
   - Explain *why*, not just *what*.
   - Reference MQTT 5.0 spec sections when relevant (e.g., "per §3.3.2.3").
</instructions>
