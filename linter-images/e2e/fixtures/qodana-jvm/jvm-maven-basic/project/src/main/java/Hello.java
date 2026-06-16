public class Hello {
    // StringEquality: comparing strings with `==` instead of `.equals()`.
    public boolean equalsBroken(String a, String b) {
        return a == b;
    }

    // EmptyCatchBlock: catch block with nothing in it.
    public void swallowExceptions() {
        try {
            doStuff();
        } catch (Exception e) {
            // intentionally empty
        }
    }

    void doStuff() {
        throw new RuntimeException();
    }

    // DuplicatedCode pair #1: long enough to clear the duplicate-detector
    // token threshold. Kept byte-for-byte identical to sumWidgetsB below.
    int sumWidgetsA(int[] xs) {
        int total = 0;
        for (int x : xs) {
            int weighted = x * 3 + 7;
            int clamped = weighted > 100 ? 100 : weighted;
            total += clamped;
        }
        return total;
    }

    // DuplicatedCode pair #2: identical body to sumWidgetsA.
    int sumWidgetsB(int[] xs) {
        int total = 0;
        for (int x : xs) {
            int weighted = x * 3 + 7;
            int clamped = weighted > 100 ? 100 : weighted;
            total += clamped;
        }
        return total;
    }
}
