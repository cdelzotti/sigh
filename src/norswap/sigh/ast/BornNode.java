package norswap.sigh.ast;

import norswap.autumn.positions.Span;
import norswap.utils.Util;

public final class BornNode extends StatementNode
{
    public final ReferenceNode function;
    public final ReferenceNode variable;

    public BornNode (Span span, Object function, Object variable) {
        super(span);
        this.function = Util.cast(function, ReferenceNode.class);
        this.variable = variable == null
            ? null
            : Util.cast(variable, ReferenceNode.class);
    }

    @Override public String contents ()
    {
        return function.name;
    }
}
