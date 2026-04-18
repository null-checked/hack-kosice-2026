---
name: Test Generator
description: Generate unit tests for selected code
trigger: Generate Tests
allowed_tools: generate_tests
---

# Test Generator Skill

When invoked, analyze the selected code and generate appropriate unit tests.

## Instructions
1. Identify the function/method being tested
2. Determine edge cases and happy paths
3. Generate tests using the appropriate framework (pytest for Python, JUnit for Kotlin)
4. Include assertions for expected behavior

## Output Format
Return a complete test file with imports, test class/function, and all necessary assertions.
