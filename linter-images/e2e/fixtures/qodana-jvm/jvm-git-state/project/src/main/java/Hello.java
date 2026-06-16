public class Hello {
    // StringEquality: ensures the scan yields >=1 result in every git state,
    // so the git-state assertions (exit 0, SARIF produced, clean log) are the
    // only variables under test.
    public boolean equalsBroken(String a, String b) {
        return a == b;
    }
}
