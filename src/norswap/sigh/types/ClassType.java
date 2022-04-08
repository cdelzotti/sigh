package norswap.sigh.types;

import java.util.HashMap;
import java.util.List;

public class ClassType extends Type
{

    public final String name;
    private final HashMap<String, Type> fields;

    public ClassType (String name)
    {
        this.name = name;
        this.fields = new HashMap<>();
    }

    public boolean addKeys(String name, Type type) {
        if (!fields.containsKey(name)) {
            fields.put(name, type);
            return true;
        } else {
            return false;
        }
    }

    public Type hasField(String name) {
        return fields.get(name);
    }

    public FunType getConstructor() {
        return (FunType) fields.get("<constructor>");
    }

    public boolean canBeAssignedWith(Type other, StringBuilder error) {
        if (!(other instanceof ClassType)) {
            error.append("Cannot assign ").append(other).append(" to ").append(this);
            return false;
        }
        ClassType other_class = (ClassType) other;
        // Iterate on each field of the current class
        for (String key : fields.keySet()) {
            // If the other type has a field with the same name,
            // check if it can be assigned to
            Type currentField = fields.get(key);
            Type otherField = other_class.hasField(key);
            if (otherField == null) {
                error.append("Field ")
                        .append(key)
                        .append(" ")
                        .append(currentField.name())
                        .append(" is missing in ")
                        .append(other_class.name)
                        .append("\n");
                return false;
            }
            // Both fields must be identical
            if (!currentField.name().equals(otherField.name())) {
                error.append("Field ").append(key).append(" has different types :\n");
                error.append(currentField.name()).append(" and ").append(otherField.name());
                return false;
            }
        }
        return true;
    }

    public String name () {
        return name;
    }

    public HashMap<String, Type> getVariables() {
        HashMap<String, Type> variables = new java.util.HashMap<>();
        for (String key : fields.keySet()) {
            variables.put(key, fields.get(key));
        }
        return variables;
    }
}
