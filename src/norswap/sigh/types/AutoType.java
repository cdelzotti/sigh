package norswap.sigh.types;

public class AutoType extends  Type {
    public static final AutoType INSTANCE = new AutoType();
    private AutoType() {}

    @Override public String name() {
        return "Auto";
    }
}
