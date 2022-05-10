import norswap.autumn.AutumnTestFixture;
import norswap.sigh.SighGrammar;
import norswap.sigh.ast.*;
import norswap.sigh.types.UnbornType;
import norswap.sigh.types.VoidType;
import org.testng.annotations.Test;

import static java.util.Arrays.asList;
import static norswap.sigh.ast.BinaryOperator.*;

public class GrammarTests extends AutumnTestFixture {
    // ---------------------------------------------------------------------------------------------

    private final SighGrammar grammar = new SighGrammar();
    private final Class<?> grammarClass = grammar.getClass();

    // ---------------------------------------------------------------------------------------------

    private static IntLiteralNode intlit (long i) {
        return new IntLiteralNode(null, i);
    }

    private static FloatLiteralNode floatlit (double d) {
        return new FloatLiteralNode(null, d);
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testLiteralsAndUnary () {
        rule = grammar.expression;

        successExpect("42", intlit(42));
        successExpect("42.0", floatlit(42d));
        successExpect("\"hello\"", new StringLiteralNode(null, "hello"));
        successExpect("(42)", new ParenthesizedNode(null, intlit(42)));
        successExpect("[1, 2, 3]", new ArrayLiteralNode(null, asList(intlit(1), intlit(2), intlit(3))));
        successExpect("true", new ReferenceNode(null, "true"));
        successExpect("false", new ReferenceNode(null, "false"));
        successExpect("null", new ReferenceNode(null, "null"));
        successExpect("!false", new UnaryExpressionNode(null, UnaryOperator.NOT, new ReferenceNode(null, "false")));
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testNumericBinary () {
        successExpect("1 + 2", new BinaryExpressionNode(null, intlit(1), ADD, intlit(2)));
        successExpect("2 - 1", new BinaryExpressionNode(null, intlit(2), SUBTRACT,  intlit(1)));
        successExpect("2 * 3", new BinaryExpressionNode(null, intlit(2), MULTIPLY, intlit(3)));
        successExpect("2 / 3", new BinaryExpressionNode(null, intlit(2), DIVIDE, intlit(3)));
        successExpect("2 % 3", new BinaryExpressionNode(null, intlit(2), REMAINDER, intlit(3)));

        successExpect("1.0 + 2.0", new BinaryExpressionNode(null, floatlit(1), ADD, floatlit(2)));
        successExpect("2.0 - 1.0", new BinaryExpressionNode(null, floatlit(2), SUBTRACT, floatlit(1)));
        successExpect("2.0 * 3.0", new BinaryExpressionNode(null, floatlit(2), MULTIPLY, floatlit(3)));
        successExpect("2.0 / 3.0", new BinaryExpressionNode(null, floatlit(2), DIVIDE, floatlit(3)));
        successExpect("2.0 % 3.0", new BinaryExpressionNode(null, floatlit(2), REMAINDER, floatlit(3)));

        successExpect("2 * (4-1) * 4.0 / 6 % (2+1)", new BinaryExpressionNode(null,
            new BinaryExpressionNode(null,
                new BinaryExpressionNode(null,
                    new BinaryExpressionNode(null,
                        intlit(2),
                        MULTIPLY,
                        new ParenthesizedNode(null, new BinaryExpressionNode(null,
                            intlit(4),
                            SUBTRACT,
                            intlit(1)))),
                    MULTIPLY,
                    floatlit(4d)),
                DIVIDE,
                intlit(6)),
            REMAINDER,
            new ParenthesizedNode(null, new BinaryExpressionNode(null,
                intlit(2),
                ADD,
                intlit(1)))));
    }

    // ---------------------------------------------------------------------------------------------

    @Test public void testArrayStructAccess () {
        rule = grammar.expression;
        successExpect("[1][0]", new ArrayAccessNode(null,
            new ArrayLiteralNode(null, asList(intlit(1))), intlit(0)));
        successExpect("[1].length", new FieldAccessNode(null,
            new ArrayLiteralNode(null, asList(intlit(1))), "length"));
        successExpect("p.x", new FieldAccessNode(null, new ReferenceNode(null, "p"), "x"));
    }

    // ---------------------------------------------------------------------------------------------

    @Test public void testDeclarations() {
        rule = grammar.statement;

        successExpect("var x: Int = 1", new VarDeclarationNode(null,
            "x", new SimpleTypeNode(null, "Int"), intlit(1)));

        successExpect("struct P {}", new StructDeclarationNode(null, "P", asList()));

        successExpect("struct P { var x: Int; var y: Int }",
            new StructDeclarationNode(null, "P", asList(
                new FieldDeclarationNode(null, "x", new SimpleTypeNode(null, "Int")),
                new FieldDeclarationNode(null, "y", new SimpleTypeNode(null, "Int")))));

        successExpect("fun f (x: Int): Int { return 1 }",
            new FunDeclarationNode(null, "f",
                asList(new ParameterNode(null, "x", new SimpleTypeNode(null, "Int"))),
                new SimpleTypeNode(null, "Int"),
                new BlockNode(null, asList(new ReturnNode(null, intlit(1))))));

        successExpect("class MyClass sonOf FatherClass { var x: Int = 0 fun MyClass (X: Int) { x = X } }",
                new ClassDeclarationNode(
                        null,
                        "MyClass",
                        "FatherClass",
                        asList(
                            new VarDeclarationNode(null, "x", new SimpleTypeNode(null, "Int"), intlit(0)),
                            new FunDeclarationNode(null, "MyClass",
                                asList(new ParameterNode(null, "X", new SimpleTypeNode(null, "Int"))),
                                new SimpleTypeNode(null, "Void"),
                                new BlockNode(null,
                                    asList(
                                        new ExpressionStatementNode(null, new AssignmentNode(null,
                                        new ReferenceNode(null, "x"), new ReferenceNode(null, "X")))
                                    )))
                        )));

        successExpect("fun myFunc(): Unborn<Int> { var myVar: Int = 0 return myVar }",
            new FunDeclarationNode(
                null,
                "myFunc", 
                asList(),
                new UnbornTypeNode(null, new SimpleTypeNode(null, "Int")),
                new BlockNode(null, asList(new VarDeclarationNode(null, "myVar", new SimpleTypeNode(null, "Int"), intlit(0)), new ReturnNode(null, new ReferenceNode(null, "myVar"))))
            )
        );

        // Quick increment
        successExpect("i++",
                new ExpressionStatementNode(null,
                new AssignmentNode(null,
                new ReferenceNode(null, "i"),
                new BinaryExpressionNode(null,
                        new ReferenceNode(null, "i"),
                        BinaryOperator.ADD,
                        new IntLiteralNode(null, 1)
                )
        )));

        // Quick decrement
        successExpect("i--",
                new ExpressionStatementNode(null,
                        new AssignmentNode(null,
                                new ReferenceNode(null, "i"),
                                new BinaryExpressionNode(null,
                                        new ReferenceNode(null, "i"),
                                        SUBTRACT,
                                        new IntLiteralNode(null, 1)
                                )
        )));

        // Quick addition
        successExpect("i += 12",
                new ExpressionStatementNode(null,
                        new AssignmentNode(null,
                                new ReferenceNode(null, "i"),
                                new BinaryExpressionNode(null,
                                        new ReferenceNode(null, "i"),
                                        ADD,
                                        new IntLiteralNode(null, 12)
                                )
                        )));
        // Quick subtraction
        successExpect("i -= 12",
                new ExpressionStatementNode(null,
                        new AssignmentNode(null,
                                new ReferenceNode(null, "i"),
                                new BinaryExpressionNode(null,
                                        new ReferenceNode(null, "i"),
                                        SUBTRACT,
                                        new IntLiteralNode(null, 12)
                                )
                        )));
        // Quick multiplication
        successExpect("i *= 12",
                new ExpressionStatementNode(null,
                        new AssignmentNode(null,
                                new ReferenceNode(null, "i"),
                                new BinaryExpressionNode(null,
                                        new ReferenceNode(null, "i"),
                                        MULTIPLY,
                                        new IntLiteralNode(null, 12)
                                )
                )));

        // Quick division
        successExpect("i /= 12",
                new ExpressionStatementNode(null,
                        new AssignmentNode(null,
                                new ReferenceNode(null, "i"),
                                new BinaryExpressionNode(null,
                                        new ReferenceNode(null, "i"),
                                        DIVIDE,
                                        new IntLiteralNode(null, 12)
                                )
                        )));

        // ciblingsOf operator
        successExpect("var a : Bool = i ciblingsOf j",
                new VarDeclarationNode(null,
                        "a",
                        new SimpleTypeNode(null, "Bool"),
                        new BinaryExpressionNode(null,
                            new ReferenceNode(null, "i"),
                            CIBLINGS,
                            new ReferenceNode(null, "j")
                        )
        ));

        successExpect("var aNiceVar: Unborn<Int> = myFunc()",
            new VarDeclarationNode(
                null, 
                "aNiceVar", 
                new UnbornTypeNode(null, new SimpleTypeNode(null, "Int")),
                new FunCallNode(null, new ReferenceNode(null, "myFunc"), asList())
            )
        );
    }

    // ---------------------------------------------------------------------------------------------

    @Test public void testStatements() {
        rule = grammar.statement;

        successExpect("return", new ReturnNode(null, null));
        successExpect("return 1", new ReturnNode(null, intlit(1)));
        successExpect("print(1)", new ExpressionStatementNode(null,
            new FunCallNode(null, new ReferenceNode(null, "print"), asList(intlit(1)))));
        successExpect("{ return }", new BlockNode(null, asList(new ReturnNode(null, null))));


        successExpect("if true return 1 else return 2", new IfNode(null, new ReferenceNode(null, "true"),
            new ReturnNode(null, intlit(1)),
            new ReturnNode(null, intlit(2))));

        successExpect("if false return 1 else if true return 2 else return 3 ",
            new IfNode(null, new ReferenceNode(null, "false"),
                new ReturnNode(null, intlit(1)),
                new IfNode(null, new ReferenceNode(null, "true"),
                    new ReturnNode(null, intlit(2)),
                    new ReturnNode(null, intlit(3)))));

        successExpect("while 1 < 2 { return } ", new WhileNode(null,
            new BinaryExpressionNode(null, intlit(1), LOWER, intlit(2)),
            new BlockNode(null, asList(new ReturnNode(null, null)))));

        successExpect("born(myFunction, myVariable)",
            new BornNode(
                null,
                new ReferenceNode(null, "myFunction"),
                new ReferenceNode(null, "myVariable")
            )
        );

        successExpect("born(myFunction)",
            new BornNode(
                null,
                new ReferenceNode(null, "myFunction"),
                null
        )
        );
    }

    // ---------------------------------------------------------------------------------------------
}
