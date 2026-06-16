package excluded;

public class Sub {
    // Same StringEquality smell as planted.Planted, but under excluded/.
    public boolean broken(String a, String b) {
        return a == b;
    }
}
