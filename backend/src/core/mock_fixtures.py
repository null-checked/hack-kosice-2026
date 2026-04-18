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


# --- Pillar 3: Socratic questions, one bank per StuckDetector rule --------
# Each rule ships 2–3 variants so back-to-back triggers of the same rule
# don't deliver identical questions. `pick_socratic_mock` below rotates
# variants deterministically on `hash(code)` — the demo stays reproducible
# but the UX no longer feels like quote-of-the-day.

MOCK_SOCRATIC_UNDO_BURST = [
    {
        "question": (
            "You've undone this line four times in thirty seconds — what "
            "invariant is it supposed to maintain after each iteration?"
        ),
        "follow_ups": [
            "Which element should the accumulator represent — the value at `i`, or the next?",
            "What should be true about the accumulator before you enter the append branch?",
        ],
        "confidence": 0.88,
    },
    {
        "question": (
            "Four undoes in thirty seconds — before you rewrite it again, "
            "can you state the shape of the correct answer in one sentence?"
        ),
        "follow_ups": [
            "If you had to bet, which input would this line currently fail on?",
            "Is the bug in this line, or in what you're passing in?",
        ],
        "confidence": 0.85,
    },
]

MOCK_SOCRATIC_REPEATED_FAILING_TEST = [
    {
        "question": (
            "Same test fails again — which precondition would have to change "
            "for the assertion to hold?"
        ),
        "follow_ups": [
            "What does the failing expected value imply about the input?",
            "Is the production code wrong, or is the test asserting the wrong thing?",
        ],
        "confidence": 0.92,
    },
    {
        "question": (
            "The same failure keeps coming back — before the next run, write "
            "down what you think SHOULD happen for this input. Does it match?"
        ),
        "follow_ups": [
            "Which line runs last before the assertion fails?",
            "Can you shrink the input to the smallest case that still fails?",
        ],
        "confidence": 0.9,
    },
]

MOCK_SOCRATIC_LONG_IDLE_ON_ERROR = [
    {
        "question": (
            "You've been sitting on this red squiggly for a minute and a half — "
            "what is the compiler actually claiming it can't prove?"
        ),
        "follow_ups": [
            "Read the error message out loud. Which word is doing the work?",
            "If the error is misleading, what's the SMALLEST change that would clear it?",
        ],
        "confidence": 0.82,
    },
    {
        "question": (
            "Long pause on an error — does the marker say your code is wrong, "
            "or that the type checker lacks enough info to be sure?"
        ),
        "follow_ups": [
            "What would a narrower type here let the checker conclude?",
            "If you comment out this line, does the next error get clearer or more confusing?",
        ],
        "confidence": 0.8,
    },
]

MOCK_SOCRATIC_RAPID_FILE_SWITCHING = [
    {
        "question": (
            "You're bouncing between files — what symbol are you actually "
            "tracking? Have you tried `Find Usages` on it?"
        ),
        "follow_ups": [
            "Can you name the exact question you're answering by reading these files?",
            "Would a call-graph picture of the entry point make this faster?",
        ],
        "confidence": 0.76,
    },
    {
        "question": (
            "Six files in sixty seconds. Stop — in one sentence, what are "
            "you looking for?"
        ),
        "follow_ups": [
            "Is the thing you're searching for in the repo at all?",
            "Would `grep` for a specific string be faster than navigating?",
        ],
        "confidence": 0.7,
    },
]

MOCK_SOCRATIC_EDIT_THRASH = [
    {
        "question": (
            "This line keeps getting rewritten — is the bug on THIS line, or "
            "in what calls it?"
        ),
        "follow_ups": [
            "If the caller passed you perfect inputs, would this line still be wrong?",
            "What's the simplest test that pins down what this line is supposed to do?",
        ],
        "confidence": 0.78,
    },
    {
        "question": (
            "Ten edits on one line in twenty seconds. Before the next tweak — "
            "what is this line's job, in five words?"
        ),
        "follow_ups": [
            "Would extracting it into a named function make the contract clearer?",
            "Is the return type you want even expressible in what you have?",
        ],
        "confidence": 0.75,
    },
]


MOCK_SOCRATIC_GENERIC = [
    {
        "question": (
            "What assumption about the input would have to be wrong for your "
            "last edit to have been correct?"
        ),
        "follow_ups": [
            "Which test case would most quickly disprove that assumption?",
            "What changes between the iterations you just tried?",
        ],
        "confidence": 0.65,
    },
]


_RULE_TO_BANK: dict[str, list[dict]] = {
    "undo_burst": MOCK_SOCRATIC_UNDO_BURST,
    "repeated_failing_test": MOCK_SOCRATIC_REPEATED_FAILING_TEST,
    "long_idle_on_error": MOCK_SOCRATIC_LONG_IDLE_ON_ERROR,
    "rapid_file_switching": MOCK_SOCRATIC_RAPID_FILE_SWITCHING,
    "edit_thrash": MOCK_SOCRATIC_EDIT_THRASH,
}


def pick_socratic_mock(stuck_reason: str, current_code: str = "") -> dict:
    """Pick an opening Socratic question keyed on the StuckDetector rule.

    Within a rule, rotate across variants deterministically on `hash(code)`
    so the SAME rule firing twice in a row doesn't produce the SAME question,
    but the demo stays reproducible for identical inputs.
    """
    bank = _RULE_TO_BANK.get(stuck_reason, MOCK_SOCRATIC_GENERIC)
    if not bank:
        return MOCK_SOCRATIC_GENERIC[0]
    idx = (hash(current_code) % len(bank) + len(bank)) % len(bank)
    return bank[idx]


# Follow-up probes used when the user has already replied at least once.
# Keyed nothing but the turn index → deterministic, non-repeating walk.
_FOLLOWUP_BANK: list[dict] = [
    {
        "question": "What makes you say that? Can you point to evidence in the code?",
        "follow_ups": [
            "If you had to prove it, which line would you step through first?",
        ],
        "confidence": 0.7,
    },
    {
        "question": "How would you disprove it — what's the smallest input that would break your guess?",
        "follow_ups": [
            "If that input DIDN'T break it, would you still believe you were right?",
        ],
        "confidence": 0.72,
    },
    {
        "question": "Does that match what the debugger shows you, or is it what you EXPECT to see?",
        "follow_ups": [
            "What's the cheapest way to get real observation instead of assumption?",
        ],
        "confidence": 0.7,
    },
    {
        "question": "Say you're right — what changes about the fix? What's the next line you'd write?",
        "follow_ups": [
            "If that fix doesn't work, what does that tell you about your model?",
        ],
        "confidence": 0.68,
    },
]


def pick_socratic_followup(conversation: list) -> dict:
    """Return the next coach turn after the user has replied.

    `conversation` is a list of `{role, text}` dicts (or pydantic models with
    the same attrs). The function walks the follow-up bank by the count of
    developer turns so successive replies yield successive probes.
    """
    developer_turns = 0
    for turn in conversation:
        role = getattr(turn, "role", None) or (turn.get("role") if isinstance(turn, dict) else None)
        if role == "developer":
            developer_turns += 1
    idx = max(0, developer_turns - 1) % len(_FOLLOWUP_BANK)
    return _FOLLOWUP_BANK[idx]


# --- Inline completion (Tier C) ---
# A small library of canned completions keyed on a normalised last-line hint.
# The canonical demo case: typing `def add(a, b):\n    ` yields `return a + b`.

MOCK_COMPLETION_DEFAULT = "    pass"

MOCK_COMPLETIONS: dict[str, str] = {
    "def add": "    return a + b",
    "def sub": "    return a - b",
    "def mul": "    return a * b",
    "def div": "    return a / b if b else 0",
    "def sum": "    return sum(xs)",
    "def is_even": "    return n % 2 == 0",
    "for i in": "        print(i)",
    "if __name__": '    main()',
    "class ": "    pass",
}


def pick_completion_mock(prefix: str) -> str:
    """Pick a canned completion for `prefix` (the code BEFORE the caret).

    Match heuristic: strip trailing whitespace, find the last non-blank line,
    then check if it starts with any known key (case-sensitive). Falls back to
    `MOCK_COMPLETION_DEFAULT`.
    """
    lines = [line for line in prefix.rstrip().splitlines() if line.strip()]
    if not lines:
        return MOCK_COMPLETION_DEFAULT
    tail = lines[-1].lstrip()
    for key, value in MOCK_COMPLETIONS.items():
        if tail.startswith(key):
            return value
    return MOCK_COMPLETION_DEFAULT
