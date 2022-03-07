package norswap.sigh.scopes;

import norswap.sigh.ast.ClassDeclarationNode;
import norswap.sigh.ast.DeclarationNode;
import norswap.sigh.ast.SighNode;

import java.util.HashMap;

public class ClassScope extends Scope {

    private HashMap<String, ClassScope> classScopes;

    public ClassScope(ClassDeclarationNode node, Scope parent, HashMap<String, ClassScope> classScopes) {
        super(node, parent);
        this.classScopes = classScopes;
        classScopes.put(node.name(), this);
    }

    public DeclarationContext lookup(String name) {
        // See if we have a field or method with this name in the current class.
        DeclarationNode declaration = declarations.get(name);
        if (declaration != null) {
            return new DeclarationContext(this, declaration);
        }
        // See if we can find the field on the superclass.
        String parent = ((ClassDeclarationNode) node).parent;
        while (parent != null) {
            ClassScope classScope = classScopes.get(parent);
            if (classScope != null) {
                declaration = classScope.declarations.get(name);
                if (declaration != null) {
                    return new DeclarationContext(classScope, declaration);
                }
                parent = ((ClassDeclarationNode) classScope.node).parent;
            } else {
                parent = null;
            }
        }
        // Perform classic lookup.
        return super.lookup(name);
    }
}
