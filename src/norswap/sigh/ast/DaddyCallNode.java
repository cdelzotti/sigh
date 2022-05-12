package norswap.sigh.ast;

import norswap.autumn.positions.Span;

public class DaddyCallNode extends FunCallNode {

    public DaddyCallNode(Span span, Object function, Object arguments) {
        super(span, function, arguments);
    }
}
