---
name: Refactor Suggester
description: Suggest refactorings for selected code
trigger: Suggest Refactor
allowed_tools: suggest_refactor
---

# Refactor Suggester Skill

When invoked, analyze the selected code and suggest possible refactorings.

## Instructions
1. Identify code smells or anti-patterns (long methods, duplicated code, poor naming)
2. Suggest improvements (naming, structure, duplication removal)
3. Provide before/after code snippets
4. Explain the reasoning behind each suggestion

## Output Format
Return a list of detected smells with line numbers, followed by the refactored code.
