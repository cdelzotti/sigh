package norswap.sigh.ast;

import norswap.autumn.positions.Span;

public class MethodDeclarationNode extends FunDeclarationNode {
    private MethodDeclarationNode parent;

    public MethodDeclarationNode(Span span, Object name, Object parameters, Object returnType, Object block) {
        super(span, name, parameters, returnType, block);
    }

    public void setParent(MethodDeclarationNode parent) {
        this.parent = parent;
    }

    public MethodDeclarationNode getParent() {
        return parent;
    }
}
