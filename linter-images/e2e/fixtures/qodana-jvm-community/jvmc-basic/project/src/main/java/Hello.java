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
}
