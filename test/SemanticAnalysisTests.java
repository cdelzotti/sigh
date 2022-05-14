import norswap.autumn.AutumnTestFixture;
import norswap.autumn.positions.LineMapString;
import norswap.sigh.SemanticAnalysis;
import norswap.sigh.SighGrammar;
import norswap.sigh.ast.SighNode;
import norswap.uranium.Reactor;
import norswap.uranium.UraniumTestFixture;
import norswap.utils.visitors.Walker;
import org.testng.annotations.Test;

/**
 * NOTE(norswap): These tests were derived from the {@link InterpreterTests} and don't test anything
 * more, but show how to idiomatically test semantic analysis. using {@link UraniumTestFixture}.
 */
public final class SemanticAnalysisTests extends UraniumTestFixture
{
    // ---------------------------------------------------------------------------------------------

    private final SighGrammar grammar = new SighGrammar();
    private final AutumnTestFixture autumnFixture = new AutumnTestFixture();

    {
        autumnFixture.rule = grammar.root();
        autumnFixture.runTwice = false;
        autumnFixture.bottomClass = this.getClass();
    }

    private String input;

    @Override protected Object parse (String input) {
        this.input = input;
        return autumnFixture.success(input).topValue();
    }

    @Override protected String astNodeToString (Object ast) {
        LineMapString map = new LineMapString("<test>", input);
        return ast.toString() + " (" + ((SighNode) ast).span.startString(map) + ")";
    }

    // ---------------------------------------------------------------------------------------------

    @Override protected void configureSemanticAnalysis (Reactor reactor, Object ast) {
        Walker<SighNode> walker = SemanticAnalysis.createWalker(reactor);
        walker.walk(((SighNode) ast));
    }

    // ---------------------------------------------------------------------------------------------

    @Test public void testClasses() {
        // Normal class declaration
        successInput("class Point { var X: Int = 0 var Y: Int = 0 fun Point(x: Int, y: Int) { X = x Y = y } }");
        // Can inherit
        successInput("class Obj { fun Obj() {}}  class Point sonOf Obj { var X: Int = 0 var Y: Int = 0 fun Point(x: Int, y: Int) { X = x Y = y } }");
        // Can use members of the parent class
        successInput("class Obj { var X : Int = 0 fun Obj() {} } class Obj2 sonOf Obj {  fun Obj2() { X = 1 } }");
        // Type checking based on the shape of the class
        successInput("class Obj { var X : Int = 0 fun Obj() {} } class Obj2 { var X : Int = 0  fun Obj2() { } } var myVar: Obj2 = Obj()");
        // Matching class name to constructor
        successInput("class Point { var X : Int = 0 var Y : Int = 0 fun Point(x : Int, y : Int) {X = x Y = y} } var myVar: Point = Point(12, 13)");
        // Member call
        successInput("class Point { var X : Int = 0 var Y : Int = 0 fun Point(x : Int, y : Int) {X = x Y = y} fun getX() : Int { return X } } var myVar: Point = Point(12, 13) var myX : Int = myVar.getX()");
        successInput("class Point { var X : Int = 0 var Y : Int = 0 fun Point(x : Int, y : Int) {X = x Y = y} fun getX() : Int { return X } } var myVar: Point = Point(12, 13) myVar.Y = 14");
        // siblingsOf operator
        successInput("class Obj { var X : Int = 0 fun Obj() {} } class Obj2 { var X : Int = 0  fun Obj2() { } } var myVar: Obj2 = Obj() if (myVar siblingsOf Obj){}");
        // Daddy calls
        successInput("class Obj { var X : Int = 0 fun Obj() {} fun method(){print(\"Method\")}} class Obj2 sonOf Obj {fun Obj2() { } fun method(){Daddy()}}");

        failureInputWith("class Point { var X : Int = 0 var Y : Int = 0 fun Point(x : Int, y : Int) {X = x Y = y} } var myVar: Point = Point(12)",
                        "wrong number of arguments");
        failureInputWith("class Obj { var X : Int = 0 fun Obj() {} } class Obj2 { var Y : Int = 0  fun Obj2() { } } var myVar: Obj2 = Obj()",
            "Field", "is missing");
        failureInputWith("class Point sonOf Obj { var X: Int = 0 var Y: Int = 0 fun Point(x: Int, y: Int) { X = x Y = y } }",
                "Undeclared ancestor");
        failureInputWith("class Point sonOf Int { var X: Int = 0 var Y: Int = 0 fun Point(x: Int, y: Int) { X = x Y = y } }",
                        "not a class");
        failureInputWith("class Class1 sonOf Class2 { fun Class1() {}} class Class2 sonOf Class1 { fun Class2() {}}",
                        "Cyclic inheritance");
        failureInputWith("class Point { var X: Int = 0 var Y: Int = 0 }", "Missing constructor for class `Point`");
        failureInputWith("class Obj { var X : Int = 0 fun Obj() {} } class Obj2 { var X : Int = 0  fun Obj2() { } } var myVar: Obj2 = Obj() if (myVar siblingsOf Int){}",
                "Trying to check if two non-classes are siblings.");
        failureInputWith("class Obj { var X : Int = 0 fun Obj(){}} class Obj2 sonOf Obj {fun Obj2() { } fun method(){Daddy()}}",
                "no parent", "Cannot find parent type, does it exists ?", "missing attribute");

    }

    // ---------------------------------------------------------------------------------------------
    @Test public void testSyntacticSugar(){
        successInput("class SomeLongAndBoringClassNameThatNeverEnds {fun SomeLongAndBoringClassNameThatNeverEnds(){}} var a : Auto = SomeLongAndBoringClassNameThatNeverEnds()");
        successInput("var i : Int = 12 i++ i-- i += 1 i -= 1 i *= 1 i /= 1");
    }

    // ---------------------------------------------------------------------------------------------

    @Test public void testLiteralsAndUnary() {
        successInput("return 42");
        successInput("return 42.0");
        successInput("return \"hello\"");
        successInput("return (42)");
        successInput("return [1, 2, 3]");
        successInput("return true");
        successInput("return false");
        successInput("return null");
        successInput("return !false");
        successInput("return !true");
        successInput("return !!true");
    }

    // ---------------------------------------------------------------------------------------------

    @Test public void testNumericBinary() {
        successInput("return 1 + 2");
        successInput("return 2 - 1");
        successInput("return 2 * 3");
        successInput("return 2 / 3");
        successInput("return 3 / 2");
        successInput("return 2 % 3");
        successInput("return 3 % 2");

        successInput("return 1.0 + 2.0");
        successInput("return 2.0 - 1.0");
        successInput("return 2.0 * 3.0");
        successInput("return 2.0 / 3.0");
        successInput("return 3.0 / 2.0");
        successInput("return 2.0 % 3.0");
        successInput("return 3.0 % 2.0");

        successInput("return 1 + 2.0");
        successInput("return 2 - 1.0");
        successInput("return 2 * 3.0");
        successInput("return 2 / 3.0");
        successInput("return 3 / 2.0");
        successInput("return 2 % 3.0");
        successInput("return 3 % 2.0");

        successInput("return 1.0 + 2");
        successInput("return 2.0 - 1");
        successInput("return 2.0 * 3");
        successInput("return 2.0 / 3");
        successInput("return 3.0 / 2");
        successInput("return 2.0 % 3");
        successInput("return 3.0 % 2");

        failureInputWith("return 2 + true", "Trying to add Int with Bool");
        failureInputWith("return true + 2", "Trying to add Bool with Int");
        failureInputWith("return 2 + [1]", "Trying to add Int with Int[]");
        failureInputWith("return [1] + 2", "Trying to add Int[] with Int");
    }

    // ---------------------------------------------------------------------------------------------

    @Test public void testOtherBinary() {
        successInput("return true && false");
        successInput("return false && true");
        successInput("return true && true");
        successInput("return true || false");
        successInput("return false || true");
        successInput("return false || false");

        failureInputWith("return false || 1",
            "Attempting to perform binary logic on non-boolean type: Int");
        failureInputWith("return 2 || true",
            "Attempting to perform binary logic on non-boolean type: Int");

        successInput("return 1 + \"a\"");
        successInput("return \"a\" + 1");
        successInput("return \"a\" + true");

        successInput("return 1 == 1");
        successInput("return 1 == 2");
        successInput("return 1.0 == 1.0");
        successInput("return 1.0 == 2.0");
        successInput("return true == true");
        successInput("return false == false");
        successInput("return true == false");
        successInput("return 1 == 1.0");

        failureInputWith("return true == 1", "Trying to compare incomparable types Bool and Int");
        failureInputWith("return 2 == false", "Trying to compare incomparable types Int and Bool");

        successInput("return \"hi\" == \"hi\"");
        successInput("return [1] == [1]");

        successInput("return 1 != 1");
        successInput("return 1 != 2");
        successInput("return 1.0 != 1.0");
        successInput("return 1.0 != 2.0");
        successInput("return true != true");
        successInput("return false != false");
        successInput("return true != false");
        successInput("return 1 != 1.0");

        failureInputWith("return true != 1", "Trying to compare incomparable types Bool and Int");
        failureInputWith("return 2 != false", "Trying to compare incomparable types Int and Bool");

        successInput("return \"hi\" != \"hi\"");
        successInput("return [1] != [1]");
    }

    // ---------------------------------------------------------------------------------------------

    @Test public void testVarDecl() {
        successInput("var x: Int = 1; return x");
        successInput("var x: Float = 2.0; return x");

        successInput("var x: Int = 0; return x = 3");
        successInput("var x: String = \"0\"; return x = \"S\"");

        failureInputWith("var x: Int = true", "expected Int but got Bool");
        failureInputWith("return x + 1", "Could not resolve: x");
        failureInputWith("return x + 1; var x: Int = 2", "Variable used before declaration: x");

        // implicit conversions
        successInput("var x: Float = 1 ; x = 2");
    }

    // ---------------------------------------------------------------------------------------------

    @Test public void testRootAndBlock () {
        successInput("return");
        successInput("return 1");
        successInput("return 1; return 2");

        successInput("print(\"a\")");
        successInput("print(\"a\" + 1)");
        successInput("print(\"a\"); print(\"b\")");

        successInput("{ print(\"a\"); print(\"b\") }");

        successInput(
            "var x: Int = 1;" +
            "{ print(\"\" + x); var x: Int = 2; print(\"\" + x) }" +
            "print(\"\" + x)");
    }

    // ---------------------------------------------------------------------------------------------

    @Test public void testCalls() {
        successInput(
            "fun add (a: Int, b: Int): Int { return a + b } " +
            "return add(4, 7)");

        successInput(
            "struct Point { var x: Int; var y: Int }" +
            "return $Point(1, 2)");

        successInput("var str: String = null; return print(str + 1)");

        failureInputWith("return print(1)", "argument 0 in print: expected String but got Int");
    }

    // ---------------------------------------------------------------------------------------------

    @Test public void testArrayStructAccess() {
        successInput("return [1][0]");
        successInput("return [1.0][0]");
        successInput("return [1, 2][1]");

        failureInputWith("return [1][true]", "Indexing an array using a non-Int-valued expression");

        // TODO make this legal?
        // successInput("[].length", 0L);

        successInput("return [1].length");
        successInput("return [1, 2].length");

        successInput("var array: Int[] = null; return array[0]");
        successInput("var array: Int[] = null; return array.length");

        successInput("var x: Int[] = [0, 1]; x[0] = 3; return x[0]");
        successInput("var x: Int[] = []; x[0] = 3; return x[0]");
        successInput("var x: Int[] = null; x[0] = 3");

        successInput(
            "struct P { var x: Int; var y: Int }" +
            "return $P(1, 2).y");

        successInput(
            "struct P { var x: Int; var y: Int }" +
            "var p: P = null;" +
            "return p.y");

        successInput(
            "struct P { var x: Int; var y: Int }" +
            "var p: P = $P(1, 2);" +
            "p.y = 42;" +
            "return p.y");

        successInput(
            "struct P { var x: Int; var y: Int }" +
            "var p: P = null;" +
            "p.y = 42");

        failureInputWith(
            "struct P { var x: Int; var y: Int }" +
            "return $P(1, true)",
            "argument 1 in $P: expected Int but got Bool");

        failureInputWith(
            "struct P { var x: Int; var y: Int }" +
            "return $P(1, 2).z",
            "Trying to access missing field z on struct P");
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testIfWhile () {
        successInput("if (true) return 1 else return 2");
        successInput("if (false) return 1 else return 2");
        successInput("if (false) return 1 else if (true) return 2 else return 3 ");
        successInput("if (false) return 1 else if (false) return 2 else return 3 ");

        successInput("var i: Int = 0; while (i < 3) { print(\"\" + i); i = i + 1 } ");

        failureInputWith("if 1 return 1",
            "If statement with a non-boolean condition of type: Int");
        failureInputWith("while 1 return 1",
            "While statement with a non-boolean condition of type: Int");
    }

    // ---------------------------------------------------------------------------------------------

    @Test public void testInference() {
        successInput("var array: Int[] = []");
        successInput("var array: String[] = []");
        successInput("fun use_array (array: Int[]) {} ; use_array([])");
    }

    // ---------------------------------------------------------------------------------------------

    @Test public void testTypeAsValues() {
        successInput("struct S{} ; return \"\"+ S");
        successInput("struct S{} ; var type: Type = S ; return \"\"+ type");
    }

    // ---------------------------------------------------------------------------------------------

    @Test public void testUnconditionalReturn()
    {
        successInput("fun f(): Int { if (true) return 1 else return 2 } ; return f()");

        // TODO: would be nice if this pinpointed the if-statement as missing the return,
        //   not the whole function declaration
        failureInputWith("fun f(): Int { if (true) return 1 } ; return f()",
            "Missing return in function");
    }

    // ---------------------------------------------------------------------------------------------
}
