public class Hello {
    // StringEquality: comparing strings with `==` instead of `.equals()`.
    public boolean equalsBroken(String a, String b) {
        return a == b;
    }

    // EmptyCatchBlock: a TRULY empty catch block. A comment inside counts as
    // content and suppresses the inspection, so the block is left bare.
    public void swallowExceptions() {
        try {
            doStuff();
        } catch (Exception e) {
        }
    }

    void doStuff() {
        throw new RuntimeException();
    }

    // DuplicatedCode pair #1: a long, byte-for-byte identical body to
    // sumWidgetsB, sized well past the duplicate-detector threshold.
    int sumWidgetsA(int[] xs) {
        int total = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int x : xs) {
            int weighted = x * 3 + 7;
            int clamped = weighted > 100 ? 100 : weighted;
            int adjusted = clamped < 0 ? 0 : clamped;
            total += adjusted;
            if (adjusted < min) min = adjusted;
            if (adjusted > max) max = adjusted;
        }
        int span = max - min;
        return total + span;
    }

    // DuplicatedCode pair #2: identical body to sumWidgetsA.
    int sumWidgetsB(int[] xs) {
        int total = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int x : xs) {
            int weighted = x * 3 + 7;
            int clamped = weighted > 100 ? 100 : weighted;
            int adjusted = clamped < 0 ? 0 : clamped;
            total += adjusted;
            if (adjusted < min) min = adjusted;
            if (adjusted > max) max = adjusted;
        }
        int span = max - min;
        return total + span;
    }
}
