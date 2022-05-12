package norswap.sigh.types;

import norswap.sigh.ast.ClassDeclarationNode;

import java.util.HashMap;
import java.util.List;

public class ClassType extends Type
{

    public final String name;
    private final HashMap<String, Type> fields;

    public ClassType (String name) {
        this.name = name;
        this.fields = new HashMap<>();
    }

    /*
    *  Add a field to the class type
    *
    * @param name the name of the field
    * @param type the type of the field
    * @return an error value defining the result of the operation :
    *         0 if the field doesn't cause any problem
    *         1 if the field is not overridable
    *         2 if the field is a classType (overridable) but cannot be assigned to the replaced classType
    * */
    public int addKeys(String name, Type type, StringBuilder error) {
        if (!fields.containsKey(name)) {
            fields.put(name, type);
            return 0;
        } else if (type instanceof ClassType) {
//           // TODO : Maybe allows to override classType ?
//            Type newType = fields.get(name);
//            ClassType oldType = (ClassType) type;
//            if (!oldType.canBeAssignedWith(newType, error)){
//                return 2;
//            };
//            return 0;
            return 1;
        } else if (type instanceof FunType) {
            Type declaredType = fields.get(name);
            if (!declaredType.name().equals(type.name())) {
                error.append("Trying to override a method ").append(type.name()).append(" by ").append(declaredType.name())
                        .append(" : Overriding should respect parent signature");
                return 3;
            }
            return 0;
        }
        return 1;
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
            if (key.equals("<constructor>")) {
                continue;
            }
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
