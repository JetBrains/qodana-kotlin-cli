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
}
