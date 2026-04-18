"""Pre-canned responses for `DEMO_MOCK=1` mode.

Keys deliberately match the exact code shapes in docs/DEMO_SCRIPT.md so the
demo is guaranteed to work even if Ollama is unreachable.
"""

from __future__ import annotations

# --- Pillar 1: Generate Tests for fizzbuzz ---
FIZZBUZZ_TEST_CODE = '''import pytest
from fizz import fizzbuzz


@pytest.mark.parametrize(
    "n,expected",
    [
        (1, "1"),
        (2, "2"),
        (3, "Fizz"),
        (5, "Buzz"),
        (6, "Fizz"),
        (10, "Buzz"),
        (15, "FizzBuzz"),
        (30, "FizzBuzz"),
        (0, "FizzBuzz"),
        (-15, "FizzBuzz"),
        (-3, "Fizz"),
    ],
)
def test_fizzbuzz(n, expected):
    assert fizzbuzz(n) == expected
'''

MOCK_GENERATE_TESTS = {
    "test_code": FIZZBUZZ_TEST_CODE,
    "test_file_name": "test_fizz.py",
    "framework": "pytest",
    "reasoning": (
        "Parametrized across the four response branches (FizzBuzz, Fizz, Buzz, "
        "numeric) plus boundary inputs 0 and negatives which exercise the "
        "modulo-15 edge case."
    ),
}


# --- Pillar 2: Suggest Refactor for the triple-nested `analyze` ---
ANALYZE_REFACTORED = '''import itertools

SECONDS_PER_DAY = 86400


def analyze(xs):
    positives = [x for x in xs if x > 0]
    return [
        (a, b, c)
        for a, b, c in itertools.combinations_with_replacement(positives, 3)
        if a + b + c == SECONDS_PER_DAY
    ]
'''

ANALYZE_ORIGINAL = '''def analyze(xs):
    results = []
    for i in range(len(xs)):
        for j in range(len(xs)):
            for k in range(len(xs)):
                if xs[i] + xs[j] + xs[k] == 86400:
                    if xs[i] > 0 and xs[j] > 0 and xs[k] > 0:
                        results.append((xs[i], xs[j], xs[k]))
    return results
'''


def _mock_unified_diff() -> str:
    import difflib

    diff = difflib.unified_diff(
        ANALYZE_ORIGINAL.splitlines(keepends=True),
        ANALYZE_REFACTORED.splitlines(keepends=True),
        fromfile="a/src/analyze.py",
        tofile="b/src/analyze.py",
        n=3,
    )
    return "".join(diff)


MOCK_SUGGEST_REFACTOR = {
    "refactored_code": ANALYZE_REFACTORED,
    "diff_unified": _mock_unified_diff(),
    "smells_detected": [
        {
            "type": "nested_loops",
            "line": 3,
            "description": "Triple-nested O(n³) loop; replace with itertools combinations.",
        },
        {
            "type": "magic_number",
            "line": 6,
            "description": "Replace literal 86400 with SECONDS_PER_DAY constant.",
        },
        {
            "type": "duplicate_predicate",
            "line": 7,
            "description": "Positivity checks repeated; pre-filter once into `positives`.",
        },
    ],
    "reasoning": (
        "Flattened the O(n³) scan into itertools.combinations_with_replacement, "
        "pre-filtered positives, and hoisted the magic number into a named constant."
    ),
}


# --- Pillar 3: Socratic question for undo_burst on rolling_sum ---
MOCK_SOCRATIC_UNDO_BURST = {
    "question": (
        "You've undone this line four times in thirty seconds — what invariant "
        "is `total` supposed to maintain after each iteration?"
    ),
    "follow_ups": [
        "Which element should `total` represent — the sum ending at `i`, or the next?",
        "What should be true about `total` before you enter the append branch?",
    ],
    "confidence": 0.88,
}


MOCK_SOCRATIC_GENERIC = {
    "question": (
        "What assumption about the input would have to be wrong for your last "
        "edit to have been correct?"
    ),
    "follow_ups": [
        "Which test case would most quickly disprove that assumption?",
        "What changes between the iterations you just tried?",
    ],
    "confidence": 0.65,
}


def pick_socratic_mock(stuck_reason: str) -> dict:
    if stuck_reason == "undo_burst":
        return MOCK_SOCRATIC_UNDO_BURST
    return MOCK_SOCRATIC_GENERIC
