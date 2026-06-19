<?php
// PHP inspection planted defects for the qodana-php e2e.

// PhpUnusedLocalVariable: $unused is assigned and never read.
function compute(int $value): int
{
    $unused = $value * 2;
    return $value + 1;
}

// TypeUnsafeComparison: a non-strict `==` between a string and an int literal (use `===`).
function describe(string $name): string
{
    if ($name == 0) {
        return "zero-ish: " . $name;
    }
    return "ok: " . $name;
}

echo compute(41), PHP_EOL;
echo describe("world"), PHP_EOL;
