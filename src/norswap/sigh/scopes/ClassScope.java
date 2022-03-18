package norswap.sigh.scopes;

import norswap.sigh.ast.ClassDeclarationNode;
import norswap.sigh.ast.DeclarationNode;
import norswap.sigh.ast.SighNode;
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
}
