package norswap.sigh.scopes;

import norswap.sigh.ast.ClassDeclarationNode;
import norswap.sigh.ast.DeclarationNode;
import norswap.sigh.ast.SighNode;
import norswap.sigh.types.ClassType;
import norswap.sigh.types.Type;
import norswap.uranium.Reactor;

import java.util.ArrayList;
import java.util.HashMap;

public class ClassScope extends Scope {

    private final HashMap<ClassDeclarationNode, ClassScope> classScopes;

    public ClassScope(ClassDeclarationNode node, Scope parent, HashMap<ClassDeclarationNode, ClassScope> classScopes, ClassDeclarationNode currentClass) {
        super(node, parent);
        this.classScopes = classScopes;
        classScopes.put(currentClass, this);
    }

    @Override
    public DeclarationContext lookup(String name) {
        // See if we have a field or method with this name in the current class.
        DeclarationNode declaration = declarations.get(name);
        if (declaration != null) {
            return new DeclarationContext(this, declaration);
        }
        // Find parent scope.
        String parent = ((ClassDeclarationNode) node).parent;
        ArrayList<String> parents = new ArrayList<>();
        while (parent != null && !parents.contains(parent)) {
            // Find parent context
            DeclarationContext parentContext = super.lookup(parent);
            if (parentContext != null && parentContext.declaration instanceof ClassDeclarationNode) {
                // Parent exists, find its scope
                ClassScope parentScope = this.classScopes.get(parentContext.declaration);
                // Find the field or method in the parent scope
                DeclarationNode parentDeclaration = parentScope.declarations.get(name);
                if (parentDeclaration != null) {
                    return new DeclarationContext(parentScope, parentDeclaration);
                }
                parents.add(parent);
                parent = ((ClassDeclarationNode) parentContext.declaration).parent;
            } else {
                parent = null;
            }
        }
        // Perform classic lookup.
        return super.lookup(name);
    }

//    public boolean canBeAssignedWith(ClassScope other, StringBuilder error) {
//        for (String key : fields.keySet()) {
//            // If the other type has a field with the same name,
//            // check if it can be assigned to
//            Type currentField = fields.get(key);
//            Type otherField = other_class.hasField(key);
//            if (otherField == null) {
//                error.append("Field ")
//                        .append(key)
//                        .append(" ")
//                        .append(currentField.name())
//                        .append(" is missing in ")
//                        .append(other_class.name)
//                        .append("\n");
//                return false;
//            }
//            // Both fields must be identical
//            if (!currentField.name().equals(otherField.name())) {
//                error.append("Field ").append(key).append(" has different types :\n");
//                error.append(currentField.name()).append(" and ").append(otherField.name());
//                return false;
//            }
//        }
//        return true;
//    }
}
